/*
 * Copyright (c) 2024 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.autofill.impl.ui.credential.management.survey

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.OnIo
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import dagger.SingleInstanceIn
import javax.inject.Inject

interface AutofillSurveyStore {
    suspend fun hasSurveyBeenTaken(id: String): Boolean
    suspend fun recordSurveyWasShown(id: String)
    suspend fun resetPreviousSurveys()
    suspend fun availableSurveys(): List<SurveyDetails>
    suspend fun updateAvailableSurveys(json: String)
}

@ContributesBinding(AppScope::class, boundType = AutofillSurveyStore::class)
@SingleInstanceIn(AppScope::class)
class AutofillSurveyStoreImpl @Inject constructor(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val surveyJsonParser: AutofillSurveyJsonParser,
) : AutofillSurveyStore {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    @OnIo
    override suspend fun hasSurveyBeenTaken(id: String): Boolean {
        val previousSurveys = prefs.getStringSet(SURVEY_IDS, mutableSetOf()) ?: mutableSetOf()
        return previousSurveys.contains(id)
    }

    @OnIo
    override suspend fun recordSurveyWasShown(id: String) {
        val currentValue = prefs.getStringSet(SURVEY_IDS, mutableSetOf()) ?: mutableSetOf()
        val newValue = currentValue.toMutableSet()
        newValue.add(id)
        prefs.edit {
            putStringSet(SURVEY_IDS, newValue)
        }
    }

    @OnIo
    override suspend fun resetPreviousSurveys() {
        prefs.edit {
            remove(SURVEY_IDS)
        }
    }

    @OnIo
    override suspend fun availableSurveys(): List<SurveyDetails> {
        return kotlin.runCatching {
            val availableSurveyJson = prefs.getString(AVAILABLE_SURVEYS, null)
            surveyJsonParser.parseJson(availableSurveyJson)
        }.getOrElse { emptyList() }
    }

    @OnIo
    override suspend fun updateAvailableSurveys(json: String) {
        prefs.edit {
            putString(AVAILABLE_SURVEYS, json)
        }
    }

    companion object {
        private const val PREFS_FILE_NAME = "autofill_survey_store"
        private const val SURVEY_IDS = "survey_ids"
        private const val AVAILABLE_SURVEYS = "available_surveys"
    }
}
