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

package com.android.dialer.searchfragment.cp2;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.text.TextUtils;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.QuickContactBadge;

import androidx.annotation.IntDef;
import androidx.recyclerview.widget.RecyclerView;

import com.android.dialer.R;
import com.android.dialer.common.Assert;
import com.android.dialer.contactphoto.ContactPhotoManager;
import com.android.dialer.lettertile.LetterTileDrawable;
import com.android.dialer.searchfragment.common.Projections;
import com.android.dialer.searchfragment.common.QueryBoldingUtil;
import com.android.dialer.searchfragment.common.RowClickListener;
import com.android.dialer.searchfragment.common.SearchCursor;
import com.android.dialer.widget.BidiTextView;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** ViewHolder for a contact row. */
public final class SearchContactViewHolder extends RecyclerView.ViewHolder
        implements OnClickListener {

  /** IntDef for the different types of actions that can be shown. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    CallToAction.NONE,
    CallToAction.VIDEO_CALL
  })
  @interface CallToAction {
    int NONE = 0;
    int VIDEO_CALL = 1;
  }

  private final RowClickListener listener;
  private final QuickContactBadge photo;
  private final BidiTextView nameOrNumberView;
  private final BidiTextView numberView;
  private final ImageView callToActionView;
  private final Context context;

  private int position;
  private String number;
  private @CallToAction int currentAction;

  public SearchContactViewHolder(View view, RowClickListener listener) {
    super(view);
    this.listener = listener;
    view.setOnClickListener(this);
    photo = view.findViewById(R.id.photo);
    nameOrNumberView = view.findViewById(R.id.primary);
    numberView = view.findViewById(R.id.secondary);
    callToActionView = view.findViewById(R.id.call_to_action);
    context = view.getContext();
  }

  /**
   * Binds the ViewHolder with a cursor from {@link SearchContactsCursorLoader} with the data found
   * at the cursors set position.
   */
  public void bind(SearchCursor cursor, String query) {
    position = cursor.getPosition();
    number = cursor.getString(Projections.PHONE_NUMBER);
    String name = cursor.getString(Projections.DISPLAY_NAME);
    String label = getLabel(context.getResources(), cursor);
    String secondaryInfo =
        TextUtils.isEmpty(label)
            ? number
            : context.getString(
                R.string.call_subject_type_and_number,
                label,
                number);

    nameOrNumberView.setText(QueryBoldingUtil.getNameWithQueryBolded(query, name, context));
    numberView.setText(QueryBoldingUtil.getNumberWithQueryBolded(query, secondaryInfo));
    setCallToAction(cursor, query);

    if (shouldShowPhoto(cursor)) {
      nameOrNumberView.setVisibility(View.VISIBLE);
      photo.setVisibility(View.VISIBLE);
      String photoUri = cursor.getString(Projections.PHOTO_URI);
      ContactPhotoManager.getInstance(context)
          .loadDialerThumbnailOrPhoto(
              photo,
              getContactUri(cursor),
              cursor.getLong(Projections.PHOTO_ID),
              photoUri == null ? null : Uri.parse(photoUri),
              name,
              LetterTileDrawable.TYPE_DEFAULT);
    } else {
      nameOrNumberView.setVisibility(View.GONE);
      photo.setVisibility(View.INVISIBLE);
    }
  }

  // Show the contact photo next to only the first number if a contact has multiple numbers
  private boolean shouldShowPhoto(SearchCursor cursor) {
    int currentPosition = cursor.getPosition();
    String currentLookupKey = cursor.getString(Projections.LOOKUP_KEY);
    cursor.moveToPosition(currentPosition - 1);

    if (!cursor.isHeader() && !cursor.isBeforeFirst()) {
      String previousLookupKey = cursor.getString(Projections.LOOKUP_KEY);
      cursor.moveToPosition(currentPosition);
      return !currentLookupKey.equals(previousLookupKey);
    }
    cursor.moveToPosition(currentPosition);
    return true;
  }

  private static Uri getContactUri(Cursor cursor) {
    long contactId = cursor.getLong(Projections.ID);
    String lookupKey = cursor.getString(Projections.LOOKUP_KEY);
    return Contacts.getLookupUri(contactId, lookupKey);
  }

  // TODO(calderwoodra): handle CNAP and cequint types.
  // TODO(calderwoodra): unify this into a utility method with CallLogAdapter#getNumberType
  private static String getLabel(Resources resources, Cursor cursor) {
    int numberType = cursor.getInt(Projections.PHONE_TYPE);
    String numberLabel = cursor.getString(Projections.PHONE_LABEL);

    // Returns empty label instead of "custom" if the custom label is empty.
    if (numberType == Phone.TYPE_CUSTOM && TextUtils.isEmpty(numberLabel)) {
      return "";
    }
    return (String) Phone.getTypeLabel(resources, numberType, numberLabel);
  }

  private void setCallToAction(SearchCursor cursor, String query) {
    currentAction = getCallToAction(context, cursor, query);
    switch (currentAction) {
      case CallToAction.NONE:
        callToActionView.setVisibility(View.GONE);
        callToActionView.setOnClickListener(null);
        break;
      case CallToAction.VIDEO_CALL:
        callToActionView.setVisibility(View.VISIBLE);
        callToActionView.setImageDrawable(
            context.getDrawable(R.drawable.quantum_ic_videocam_vd_white_24));
        callToActionView.setContentDescription(
            context.getString(R.string.description_search_video_call));
        callToActionView.setOnClickListener(this);
        break;
      default:
        throw Assert.createIllegalStateFailException(
            "Invalid Call to action type: " + currentAction);
    }
  }

  private static @CallToAction int getCallToAction(
      Context context, SearchCursor cursor, String query) {
    int carrierPresence = cursor.getInt(Projections.CARRIER_PRESENCE);
    String number = cursor.getString(Projections.PHONE_NUMBER);
    if ((carrierPresence & Phone.CARRIER_PRESENCE_VT_CAPABLE) == 1) {
      return CallToAction.VIDEO_CALL;
    }

    return CallToAction.NONE;
  }

  @Override
  public void onClick(View view) {
    if (view == callToActionView) {
      switch (currentAction) {
        case CallToAction.VIDEO_CALL:
          listener.placeVideoCall(number, position);
          break;
        case CallToAction.NONE:
        default:
          throw Assert.createIllegalStateFailException(
              "Invalid Call to action type: " + currentAction);
      }
    } else {
      listener.placeVoiceCall(number, position);
    }
  }
}
