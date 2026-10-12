/*
 * Copyright (c) 2025 DuckDuckGo
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

package com.duckduckgo.pir.impl.store

import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.common.utils.CurrentTimeProvider
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.OnIo
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.pir.impl.models.scheduling.JobRecord.EmailConfirmationJobRecord
import com.duckduckgo.pir.impl.models.scheduling.JobRecord.OptOutJobRecord
import com.duckduckgo.pir.impl.models.scheduling.JobRecord.OptOutJobRecord.OptOutJobStatus
import com.duckduckgo.pir.impl.models.scheduling.JobRecord.ScanJobRecord
import com.duckduckgo.pir.impl.models.scheduling.JobRecord.ScanJobRecord.ScanJobStatus
import com.duckduckgo.pir.impl.store.db.EmailConfirmationJobRecordEntity
import com.duckduckgo.pir.impl.store.db.JobSchedulingDao
import com.duckduckgo.pir.impl.store.db.OptOutJobRecordEntity
import com.duckduckgo.pir.impl.store.db.ReportingRecord
import com.duckduckgo.pir.impl.store.db.ScanJobRecordEntity
import com.duckduckgo.pir.impl.store.secure.PirSecureStorageDatabaseFactory
import com.squareup.anvil.annotations.ContributesBinding
import dagger.SingleInstanceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import logcat.LogPriority.ERROR
import logcat.logcat
import javax.inject.Inject

interface PirSchedulingRepository {
    /**
     * Returns all ScanJobRecord whose state is not INVALID
     */
    suspend fun getAllValidScanJobRecords(): List<ScanJobRecord>

    /**
     * Returns a matching ScanJobRecord whose state is not INVALID
     */
    suspend fun getValidScanJobRecord(
        brokerName: String,
        userProfileId: Long,
    ): ScanJobRecord?

    /**
     * Returns all ScanJobRecord whose state is not INVALID
     */
    suspend fun getAllValidOptOutJobRecords(): List<OptOutJobRecord>

    /**
     * Returns all ScanJobRecord whose state is not INVALID for a specific broker
     */
    suspend fun getAllValidOptOutJobRecordsForBroker(brokerName: String): List<OptOutJobRecord>

    /**
     * Returns a matching [OptOutJobRecord] whose state is not INVALID
     *
     * @param includeDeprecated If true, will also return deprecated jobs (used to run opt-out jobs on profiles that have been removed)
     */
    suspend fun getValidOptOutJobRecord(
        extractedProfileId: Long,
        includeDeprecated: Boolean = false,
    ): OptOutJobRecord?

    suspend fun updateScanJobRecordStatus(
        newStatus: ScanJobStatus,
        newLastScanDateMillis: Long,
        brokerName: String,
        profileQueryId: Long,
        deprecated: Boolean,
    )

    suspend fun saveScanJobRecord(scanJobRecord: ScanJobRecord)

    suspend fun saveScanJobRecords(scanJobRecords: List<ScanJobRecord>)

    suspend fun saveOptOutJobRecord(optOutJobRecord: OptOutJobRecord)

    suspend fun saveOptOutJobRecords(optOutJobRecords: List<OptOutJobRecord>)

    suspend fun deleteAllScanJobRecords()

    /**
     * Deletes all job records for the given [profileQueryIds].
     *
     * This is used when a profile is deleted by the user and doesn't have any extracted profile associated to it
     * and we should no longer run any jobs on it.
     */
    suspend fun deleteJobRecordsForProfiles(profileQueryIds: List<Long>)

    /**
     * Deletes all scan job records for the given [profileQueryIds] that are do not have status MATCHES_FOUND.
     *
     * This is used when a profile is deleted by the user, but we want to keep the scan job records for the brokers
     * that have an extracted profile associated to it to continue running scan jobs on them.
     */
    suspend fun deleteScanJobRecordsWithoutMatchesForProfiles(profileQueryIds: List<Long>)

    suspend fun deleteAllOptOutJobRecords()

    suspend fun saveEmailConfirmationJobRecord(emailConfirmationJobRecord: EmailConfirmationJobRecord)

    suspend fun getEmailConfirmationJobsWithNoLink(): List<EmailConfirmationJobRecord>

    suspend fun getEmailConfirmationJobsWithLink(): List<EmailConfirmationJobRecord>

    suspend fun getEmailConfirmationJob(extractedProfileId: Long): EmailConfirmationJobRecord?

    suspend fun deleteEmailConfirmationJobRecord(extractedProfileId: Long)

    suspend fun deleteAllEmailConfirmationJobRecords()

    suspend fun markOptOutDay7ConfirmationPixelSent(
        extractedProfileId: Long,
        timestampMs: Long,
    )

    suspend fun markOptOutDay14ConfirmationPixelSent(
        extractedProfileId: Long,
        timestampMs: Long,
    )

    suspend fun markOptOutDay21ConfirmationPixelSent(
        extractedProfileId: Long,
        timestampMs: Long,
    )

    suspend fun markOptOutDay42ConfirmationPixelSent(
        extractedProfileId: Long,
        timestampMs: Long,
    )

    suspend fun clearAllData()
}

@ContributesBinding(
    scope = AppScope::class,
    boundType = PirSchedulingRepository::class,
)
@SingleInstanceIn(AppScope::class)
class RealPirSchedulingRepository @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
    private val currentTimeProvider: CurrentTimeProvider,
    private val databaseFactory: PirSecureStorageDatabaseFactory,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : PirSchedulingRepository {

    private val database: Deferred<PirDatabase?> = appCoroutineScope.async(start = CoroutineStart.LAZY) {
        prepareDatabase()
    }

    @OnIo
    override suspend fun getAllValidScanJobRecords(): List<ScanJobRecord> {
        return jobSchedulingDao()
            ?.getAllScanJobRecords()
            ?.map { it.toRecord() }
            // do not pick-up deprecated jobs as they belong to removed profiles
            ?.filter { !it.deprecated }
            .orEmpty()
    }

    @OnIo
    override suspend fun getValidScanJobRecord(
        brokerName: String,
        userProfileId: Long,
    ): ScanJobRecord? {
        return jobSchedulingDao()
            ?.getScanJobRecord(brokerName, userProfileId)
            ?.run { this.toRecord() }
            // do not pick-up deprecated jobs as they belong to removed profiles
            ?.takeIf { !it.deprecated }
    }

    @OnIo
    override suspend fun getValidOptOutJobRecord(
        extractedProfileId: Long,
        includeDeprecated: Boolean,
    ): OptOutJobRecord? {
        return jobSchedulingDao()
            ?.getOptOutJobRecord(extractedProfileId)
            ?.run { this.toRecord() }
            // do not pick-up deprecated jobs as they belong to removed profiles
            ?.takeIf { includeDeprecated || !it.deprecated }
    }

    @OnIo
    override suspend fun getAllValidOptOutJobRecords(): List<OptOutJobRecord> {
        return jobSchedulingDao()
            ?.getAllOptOutJobRecords()
            ?.map { record -> record.toRecord() }
            // do not pick-up deprecated jobs as they belong to removed profiles
            ?.filter { !it.deprecated }
            .orEmpty()
    }

    @OnIo
    override suspend fun getAllValidOptOutJobRecordsForBroker(brokerName: String): List<OptOutJobRecord> {
        return jobSchedulingDao()
            ?.getAllOptOutJobRecordsForBroker(brokerName)
            ?.map { record -> record.toRecord() }
            // do not pick-up deprecated jobs as they belong to removed profiles
            ?.filter { !it.deprecated }
            .orEmpty()
    }

    @OnIo
    override suspend fun saveScanJobRecord(scanJobRecord: ScanJobRecord) {
        jobSchedulingDao()?.saveScanJobRecord(scanJobRecord.toEntity())
    }

    @OnIo
    override suspend fun saveScanJobRecords(scanJobRecords: List<ScanJobRecord>) {
        scanJobRecords
            .map { it.toEntity() }
            .also {
                jobSchedulingDao()?.saveScanJobRecords(it)
            }
    }

    @OnIo
    override suspend fun updateScanJobRecordStatus(
        newStatus: ScanJobStatus,
        newLastScanDateMillis: Long,
        brokerName: String,
        profileQueryId: Long,
        deprecated: Boolean,
    ) {
        jobSchedulingDao()?.updateScanJobRecordStatus(
            brokerName = brokerName,
            profileQueryId = profileQueryId,
            newStatus = newStatus.name,
            newLastScanDateMillis = newLastScanDateMillis,
            deprecated = deprecated,
        )
    }

    @OnIo
    override suspend fun saveOptOutJobRecord(optOutJobRecord: OptOutJobRecord) {
        optOutJobRecord
            .toEntity()
            .also {
                jobSchedulingDao()?.saveOptOutJobRecord(it)
            }
    }

    @OnIo
    override suspend fun saveOptOutJobRecords(optOutJobRecords: List<OptOutJobRecord>) {
        optOutJobRecords
            .map { it.toEntity() }
            .also {
                jobSchedulingDao()?.saveOptOutJobRecords(it)
            }
    }

    @OnIo
    override suspend fun deleteAllScanJobRecords() {
        jobSchedulingDao()?.deleteAllScanJobRecords()
    }

    @OnIo
    override suspend fun deleteJobRecordsForProfiles(profileQueryIds: List<Long>) {
        jobSchedulingDao()?.deleteJobRecordsForProfiles(profileQueryIds)
    }

    @OnIo
    override suspend fun deleteScanJobRecordsWithoutMatchesForProfiles(profileQueryIds: List<Long>) {
        jobSchedulingDao()?.deleteScanJobRecordsWithoutMatchesForProfiles(profileQueryIds)
    }

    @OnIo
    override suspend fun deleteAllOptOutJobRecords() {
        jobSchedulingDao()?.deleteAllOptOutJobRecords()
    }

    @OnIo
    override suspend fun saveEmailConfirmationJobRecord(emailConfirmationJobRecord: EmailConfirmationJobRecord) {
        jobSchedulingDao()?.saveEmailConfirmationJobRecord(emailConfirmationJobRecord.toEntity())
    }

    @OnIo
    override suspend fun getEmailConfirmationJobsWithNoLink(): List<EmailConfirmationJobRecord> {
        return jobSchedulingDao()?.getAllActiveEmailConfirmationJobRecordsWithNoLink()?.map {
            it.toRecord()
        }.orEmpty()
    }

    @OnIo
    override suspend fun getEmailConfirmationJobsWithLink(): List<EmailConfirmationJobRecord> {
        return jobSchedulingDao()?.getAllActiveEmailConfirmationJobRecordsWithLink()?.map {
            it.toRecord()
        }.orEmpty()
    }

    @OnIo
    override suspend fun getEmailConfirmationJob(extractedProfileId: Long): EmailConfirmationJobRecord? {
        return jobSchedulingDao()?.getEmailConfirmationJobRecord(extractedProfileId)?.toRecord()
    }

    @OnIo
    override suspend fun deleteEmailConfirmationJobRecord(extractedProfileId: Long) {
        jobSchedulingDao()?.deleteEmailConfirmationJobRecord(extractedProfileId)
    }

    @OnIo
    override suspend fun deleteAllEmailConfirmationJobRecords() {
        jobSchedulingDao()?.deleteAllEmailConfirmationJobRecords()
    }

    @OnIo
    override suspend fun markOptOutDay7ConfirmationPixelSent(
        extractedProfileId: Long,
        timestampMs: Long,
    ) {
        jobSchedulingDao()?.updateSevenDayConfirmationReportSentDate(extractedProfileId, timestampMs)
    }

    @OnIo
    override suspend fun markOptOutDay14ConfirmationPixelSent(
        extractedProfileId: Long,
        timestampMs: Long,
    ) {
        jobSchedulingDao()?.update14DayConfirmationReportSentDate(extractedProfileId, timestampMs)
    }

    @OnIo
    override suspend fun markOptOutDay21ConfirmationPixelSent(
        extractedProfileId: Long,
        timestampMs: Long,
    ) {
        jobSchedulingDao()?.update21DayConfirmationReportSentDate(extractedProfileId, timestampMs)
    }

    @OnIo
    override suspend fun markOptOutDay42ConfirmationPixelSent(
        extractedProfileId: Long,
        timestampMs: Long,
    ) {
        jobSchedulingDao()?.update42DayConfirmationReportSentDate(extractedProfileId, timestampMs)
    }

    @OnIo
    override suspend fun clearAllData() {
        jobSchedulingDao()?.deleteAll()
    }

    private fun ScanJobRecordEntity.toRecord(): ScanJobRecord =
        ScanJobRecord(
            brokerName = this.brokerName,
            userProfileId = this.userProfileId,
            status = ScanJobStatus.entries.find { it.name == this.status } ?: ScanJobStatus.ERROR,
            lastScanDateInMillis = this.lastScanDateInMillis ?: 0L,
            deprecated = this.deprecated,
            dateCreatedInMillis = this.dateCreatedInMillis,
        )

    private fun ScanJobRecord.toEntity(): ScanJobRecordEntity =
        ScanJobRecordEntity(
            brokerName = this.brokerName,
            userProfileId = this.userProfileId,
            status = this.status.name,
            lastScanDateInMillis = this.lastScanDateInMillis,
            deprecated = this.deprecated,
            dateCreatedInMillis = if (this.dateCreatedInMillis != 0L) {
                this.dateCreatedInMillis
            } else {
                currentTimeProvider.currentTimeMillis()
            },
        )

    private fun OptOutJobRecordEntity.toRecord(): OptOutJobRecord =
        OptOutJobRecord(
            extractedProfileId = this.extractedProfileId,
            brokerName = this.brokerName,
            userProfileId = this.userProfileId,
            status = OptOutJobStatus.entries.find { it.name == this.status } ?: OptOutJobStatus.ERROR,
            attemptCount = this.attemptCount,
            lastOptOutAttemptDateInMillis = this.lastOptOutAttemptDate ?: 0L,
            optOutRequestedDateInMillis = this.optOutRequestedDate,
            optOutFormSubmittedDateInMillis = this.optOutFormSubmittedDate,
            optOutRemovedDateInMillis = this.optOutRemovedDate,
            deprecated = this.deprecated,
            dateCreatedInMillis = this.dateCreatedInMillis,
            confirmation7dayReportSentDateMs = this.reporting.sevenDayConfirmationReportSentDateMs,
            confirmation14dayReportSentDateMs = this.reporting.fourteenDayConfirmationReportSentDateMs,
            confirmation21dayReportSentDateMs = this.reporting.twentyOneDayConfirmationReportSentDateMs,
            confirmation42dayReportSentDateMs = this.reporting.fortyTwoDayConfirmationReportSentDateMs,
        )

    private fun OptOutJobRecord.toEntity(): OptOutJobRecordEntity =
        OptOutJobRecordEntity(
            extractedProfileId = this.extractedProfileId,
            brokerName = this.brokerName,
            userProfileId = this.userProfileId,
            status = this.status.name,
            attemptCount = this.attemptCount,
            lastOptOutAttemptDate = this.lastOptOutAttemptDateInMillis,
            optOutRequestedDate = this.optOutRequestedDateInMillis,
            optOutFormSubmittedDate = this.optOutFormSubmittedDateInMillis,
            optOutRemovedDate = this.optOutRemovedDateInMillis,
            deprecated = this.deprecated,
            dateCreatedInMillis = if (this.dateCreatedInMillis != 0L) {
                this.dateCreatedInMillis
            } else {
                currentTimeProvider.currentTimeMillis()
            },
            reporting = ReportingRecord(
                sevenDayConfirmationReportSentDateMs = this.confirmation7dayReportSentDateMs,
                fourteenDayConfirmationReportSentDateMs = this.confirmation14dayReportSentDateMs,
                twentyOneDayConfirmationReportSentDateMs = this.confirmation21dayReportSentDateMs,
                fortyTwoDayConfirmationReportSentDateMs = this.confirmation42dayReportSentDateMs,
            ),
        )

    private fun EmailConfirmationJobRecord.toEntity(): EmailConfirmationJobRecordEntity =
        EmailConfirmationJobRecordEntity(
            extractedProfileId = this.extractedProfileId,
            brokerName = this.brokerName,
            userProfileId = this.userProfileId,
            email = this.emailData.email,
            attemptId = this.emailData.attemptId,
            dateCreatedInMillis =
            if (this.dateCreatedInMillis != 0L) {
                this.dateCreatedInMillis
            } else {
                currentTimeProvider.currentTimeMillis()
            },
            emailConfirmationLink = this.linkFetchData.emailConfirmationLink,
            linkFetchAttemptCount = this.linkFetchData.linkFetchAttemptCount,
            lastLinkFetchDateInMillis = this.linkFetchData.lastLinkFetchDateInMillis,
            jobAttemptCount = this.jobAttemptData.jobAttemptCount,
            lastJobAttemptDateInMillis = this.jobAttemptData.lastJobAttemptDateInMillis,
            deprecated = this.deprecated,
        )

    private fun EmailConfirmationJobRecordEntity.toRecord(): EmailConfirmationJobRecord =
        EmailConfirmationJobRecord(
            extractedProfileId = this.extractedProfileId,
            brokerName = this.brokerName,
            userProfileId = this.userProfileId,
            emailData =
            EmailConfirmationJobRecord.EmailData(
                email = this.email,
                attemptId = this.attemptId,
            ),
            linkFetchData =
            EmailConfirmationJobRecord.LinkFetchData(
                emailConfirmationLink = this.emailConfirmationLink,
                linkFetchAttemptCount = this.linkFetchAttemptCount,
                lastLinkFetchDateInMillis = this.lastLinkFetchDateInMillis,
            ),
            jobAttemptData =
            EmailConfirmationJobRecord.JobAttemptData(
                jobAttemptCount = this.jobAttemptCount,
                lastJobAttemptDateInMillis = this.lastJobAttemptDateInMillis,
                lastJobAttemptActionId = this.lastJobAttemptActionId,
            ),
            dateCreatedInMillis = this.dateCreatedInMillis,
            deprecated = this.deprecated,
        )

    private suspend fun prepareDatabase(): PirDatabase? {
        val database = databaseFactory.getDatabase()
        return if (database != null && database.databaseContentsAreReadable()) {
            database
        } else {
            logcat(ERROR) { "PIR-DB: PIR scheduling repository is not readable" }
            null
        }
    }

    private fun PirDatabase.databaseContentsAreReadable(): Boolean {
        return kotlin.runCatching {
            // Try to read from the database to verify it's accessible
            jobSchedulingDao().getAllScanJobRecords()
            true
        }.getOrElse {
            logcat(ERROR) { "PIR-DB: Error reading from PIR scheduling repository: ${it.message}" }
            false
        }
    }

    private suspend fun jobSchedulingDao(): JobSchedulingDao? = database.await()?.jobSchedulingDao()
}
