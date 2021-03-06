/*
 * Copyright 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.riotx.features.home.room.detail.timeline.item

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import butterknife.BindView
import butterknife.ButterKnife
import im.vector.riotx.R
import im.vector.riotx.core.extensions.setTextOrHide

class PollResultLineView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    @BindView(R.id.pollResultItemLabel)
    lateinit var labelTextView: TextView

    @BindView(R.id.pollResultItemPercent)
    lateinit var percentTextView: TextView

    @BindView(R.id.pollResultItemSelectedIcon)
    lateinit var selectedIcon: ImageView

    var label: String? = null
        set(value) {
            field = value
            labelTextView.setTextOrHide(value)
        }

    var percent: String? = null
        set(value) {
            field = value
            percentTextView.setTextOrHide(value)
        }

    var optionSelected: Boolean = false
        set(value) {
            field = value
            selectedIcon.visibility = if (value) View.VISIBLE else View.INVISIBLE
        }

    var isWinner: Boolean = false
        set(value) {
            field = value
            // Text in main color
            labelTextView.setTypeface(labelTextView.getTypeface(), if (value) Typeface.BOLD else Typeface.NORMAL)
            percentTextView.setTypeface(percentTextView.getTypeface(), if (value) Typeface.BOLD else Typeface.NORMAL)
        }

    init {
        inflate(context, R.layout.item_timeline_event_poll_result_item, this)
        orientation = HORIZONTAL
        ButterKnife.bind(this)

        val typedArray = context.obtainStyledAttributes(attrs,
                R.styleable.PollResultLineView, 0, 0)
        label = typedArray.getString(R.styleable.PollResultLineView_optionName) ?: ""
        percent = typedArray.getString(R.styleable.PollResultLineView_optionCount) ?: ""
        optionSelected = typedArray.getBoolean(R.styleable.PollResultLineView_optionSelected, false)
        isWinner = typedArray.getBoolean(R.styleable.PollResultLineView_optionIsWinner, false)
    }
}
