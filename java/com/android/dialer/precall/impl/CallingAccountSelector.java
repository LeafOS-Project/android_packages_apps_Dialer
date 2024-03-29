/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2023 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.dialer.precall.impl;

import android.app.Activity;
import android.content.Context;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.android.contacts.common.widget.SelectPhoneAccountDialogFragment;
import com.android.contacts.common.widget.SelectPhoneAccountDialogOptions;
import com.android.dialer.callintent.CallIntentBuilder;
import com.android.dialer.common.Assert;
import com.android.dialer.common.LogUtil;
import com.android.dialer.precall.PreCallAction;
import com.android.dialer.precall.PreCallCoordinator;
import com.android.dialer.precall.PreCallCoordinator.PendingAction;
import com.android.dialer.preferredsim.PreferredAccountRecorder;
import com.android.dialer.preferredsim.PreferredAccountWorker;
import com.android.dialer.preferredsim.suggestion.SuggestionProvider;
import com.android.dialer.preferredsim.suggestion.SuggestionProvider.Suggestion;

import java.util.List;

import javax.inject.Inject;

/** PreCallAction to select which phone account to call with. Ignored if there's only one account */
@SuppressWarnings("MissingPermission")
public class CallingAccountSelector implements PreCallAction {

  private static final String TAG_CALLING_ACCOUNT_SELECTOR = "CallingAccountSelector";

  private SelectPhoneAccountDialogFragment selectPhoneAccountDialogFragment;

  private boolean isDiscarding;

  private final PreferredAccountWorker preferredAccountWorker;

  @Inject
  CallingAccountSelector(PreferredAccountWorker preferredAccountWorker) {
    this.preferredAccountWorker = preferredAccountWorker;
  }

  @Override
  public boolean requiresUi(Context context, CallIntentBuilder builder) {
    if (builder.getPhoneAccountHandle() != null) {
      return false;
    }

    TelephonyManager telephonyManager = context.getSystemService(TelephonyManager.class);
    if (telephonyManager.isEmergencyNumber(builder.getUri().getSchemeSpecificPart())) {
      return false;
    }

    TelecomManager telecomManager = context.getSystemService(TelecomManager.class);
    List<PhoneAccountHandle> accounts = telecomManager.getCallCapablePhoneAccounts();
    return accounts.size() > 1;
  }

  @Override
  public void runWithoutUi(Context context, CallIntentBuilder builder) {
    // do nothing.
  }

  @Override
  public void runWithUi(PreCallCoordinator coordinator) {
    CallIntentBuilder builder = coordinator.getBuilder();
    if (!requiresUi(coordinator.getActivity(), builder)) {
      return;
    }
    switch (builder.getUri().getScheme()) {
      case PhoneAccount.SCHEME_VOICEMAIL:
        showDialog(
            coordinator,
            coordinator.startPendingAction(),
            preferredAccountWorker.getVoicemailDialogOptions(),
            null,
            null,
            null);
        break;
      case PhoneAccount.SCHEME_TEL:
        processPreferredAccount(coordinator);
        break;
      default:
        // might be PhoneAccount.SCHEME_SIP
        LogUtil.e(
            "CallingAccountSelector.run",
            "unable to process scheme " + builder.getUri().getScheme());
        break;
    }
  }

  /** Initiates a background worker to find if there's any preferred account. */
  @MainThread
  private void processPreferredAccount(PreCallCoordinator coordinator) {
    Assert.isMainThread();
    CallIntentBuilder builder = coordinator.getBuilder();
    Activity activity = coordinator.getActivity();
    String phoneNumber = builder.getUri().getSchemeSpecificPart();
    PendingAction pendingAction = coordinator.startPendingAction();

    coordinator.listen(
        preferredAccountWorker.selectAccount(
            phoneNumber,
            activity.getSystemService(TelecomManager.class).getCallCapablePhoneAccounts()),
        result -> {
          if (isDiscarding) {
            // pendingAction is dropped by the coordinator before onDiscard is triggered.
            return;
          }
          if (result.getSelectedPhoneAccountHandle().isPresent()) {

            if (result.getSuggestion().isPresent()
                && result
                    .getSelectedPhoneAccountHandle()
                    .get()
                    .equals(result.getSuggestion().get().phoneAccountHandle)) {
              builder
                  .getInCallUiIntentExtras()
                  .putString(
                      SuggestionProvider.EXTRA_SIM_SUGGESTION_REASON,
                      result.getSuggestion().get().reason.name());
            }

            coordinator
                .getBuilder()
                .setPhoneAccountHandle(result.getSelectedPhoneAccountHandle().get());
            pendingAction.finish();
            return;
          }
          showDialog(
              coordinator,
              pendingAction,
              result.getDialogOptionsBuilder().get().build(),
              result.getDataId().orElse(null),
              phoneNumber,
              result.getSuggestion().orElse(null));
        },
        (throwable) -> {
          throw new RuntimeException(throwable);
        });
  }

  @MainThread
  private void showDialog(
      PreCallCoordinator coordinator,
      PendingAction pendingAction,
      SelectPhoneAccountDialogOptions dialogOptions,
      @Nullable String dataId,
      @Nullable String number,
      @Nullable Suggestion suggestion) {
    Assert.isMainThread();

    selectPhoneAccountDialogFragment =
        SelectPhoneAccountDialogFragment.newInstance(
            dialogOptions,
            new SelectedListener(
                coordinator,
                pendingAction,
                new PreferredAccountRecorder(number, suggestion, dataId)));
    selectPhoneAccountDialogFragment.show(
        ((FragmentActivity) coordinator.getActivity()).getSupportFragmentManager(),
            TAG_CALLING_ACCOUNT_SELECTOR);
  }

  @MainThread
  @Override
  public void onDiscard() {
    isDiscarding = true;
    if (selectPhoneAccountDialogFragment != null) {
      selectPhoneAccountDialogFragment.dismiss();
    }
  }

  private class SelectedListener extends
          SelectPhoneAccountDialogFragment.SelectPhoneAccountListener {

    private final PreCallCoordinator coordinator;
    private final PreCallCoordinator.PendingAction listener;
    private final PreferredAccountRecorder recorder;

    public SelectedListener(
        @NonNull PreCallCoordinator builder,
        @NonNull PreCallCoordinator.PendingAction listener,
        @NonNull PreferredAccountRecorder recorder) {
      this.coordinator = Assert.isNotNull(builder);
      this.listener = Assert.isNotNull(listener);
      this.recorder = Assert.isNotNull(recorder);
    }

    @MainThread
    @Override
    public void onPhoneAccountSelected(
        PhoneAccountHandle selectedAccountHandle, boolean setDefault, @Nullable String callId) {
      coordinator.getBuilder().setPhoneAccountHandle(selectedAccountHandle);
      recorder.record(coordinator.getActivity(), selectedAccountHandle, setDefault);
      listener.finish();
    }

    @MainThread
    @Override
    public void onDialogDismissed(@Nullable String callId) {
      if (isDiscarding) {
        return;
      }
      coordinator.abortCall();
      listener.finish();
    }
  }
}
