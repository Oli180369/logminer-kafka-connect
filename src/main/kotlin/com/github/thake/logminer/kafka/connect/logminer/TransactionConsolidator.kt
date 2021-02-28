package com.github.thake.logminer.kafka.connect.logminer

import com.github.thake.logminer.kafka.connect.CdcRecord
import com.github.thake.logminer.kafka.connect.OracleLogOffset
import com.github.thake.logminer.kafka.connect.PollResult
import com.github.thake.logminer.kafka.connect.SchemaService
import com.github.thake.logminer.sql.parser.LogminerSqlParserException
import mu.KotlinLogging
import net.openhft.chronicle.queue.ChronicleQueue
import org.apache.kafka.connect.errors.DataException
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.*
import java.util.stream.Collectors
import kotlin.math.min


class TransactionConsolidator(
        val schemaService: SchemaService
) {
    var conn : Connection? = null
    private var lastCommittedTransaction: Transaction? = null
    var lastCommitScn: Long? = null
    private val openTransactions: MutableMap<String, Transaction> = mutableMapOf()
    val hasOutstandingCommittedResults
        get() = lastCommittedTransaction?.hasMoreRecords ?: false
    var minOpenTransaction: Transaction? = null
    private val baseDir: Path =
        Files.createTempDirectory("kafaka-oracle-connect")
    private val logger = KotlinLogging.logger {}
    fun commit(commitRow: LogminerRow.Commit) {
        val recordsInTransaction = openTransactions.remove(commitRow.transaction)
        lastCommitScn = commitRow.rowIdentifier.scn
        if (recordsInTransaction != null) {
            refreshMinOpenScn()
            recordsInTransaction.commit(commitRow)
            lastCommittedTransaction = recordsInTransaction
        }
    }

    fun rollback(rollbackRow: LogminerRow.Rollback) {
        openTransactions.remove(rollbackRow.transaction)?.rollback()
        refreshMinOpenScn()
    }

    fun getOutstandingCommittedResults(batchSize: Int): List<PollResult> {
        return lastCommittedTransaction?.let { lastCommitted ->
            val loadedRecords = lastCommitted.readRecords(batchSize)
            val transactionCompleted = !lastCommitted.hasMoreRecords
            @Suppress("UNCHECKED_CAST") //Explicit != null check. Warning is a false positive
            (loadedRecords.parallelStream().map {
                try {
                    val cdcRecord = convertToCdcRecord(it, lastCommitted)
                    PollResult(
                        cdcRecord,
                        OracleLogOffset.create(
                            min(
                                it.rowIdentifier.scn,
                                minOpenTransaction?.minScn ?: Long.MAX_VALUE
                            ),
                            lastCommitted.commitScn!!,
                            transactionCompleted
                        )
                    )
                } catch (e: LogminerSqlParserException) {
                    logger.error(e) { "Skipping record for table ${it.table} with row identifier ${it.rowIdentifier}. Could not parse SQL statement \"${it.sqlRedo}\"." }
                    null
                }
            }.filter { it != null }.collect(Collectors.toList()) as List<PollResult>).also {
                if (transactionCompleted) {
                    lastCommitted.close()
                    lastCommittedTransaction = null
                }
            }
        } ?: Collections.emptyList()
    }

    private fun convertToCdcRecord(it: LogminerRow.Change, lastCommitted: Transaction): CdcRecord {
        return try {
            it.toCdcRecord(lastCommitted.transactionSchemas[it.table]!!)
        } catch (e: DataException) {
            logger.info { "Couldn't convert a logminer row to a cdc record. This may be caused by a changed schema. Schema will be refreshed and conversion will be tried again." }
            lastCommitted.updateSchemaIfOutdated(it.table)
            it.toCdcRecord(lastCommitted.transactionSchemas[it.table]!!).also {
                logger.info { "Conversion to cdc record was successful with refreshed schema." }
            }
        }
    }

    fun addChange(changeRow: LogminerRow.Change) {
        val existingOpenTransaction = openTransactions[changeRow.transaction]
        if (existingOpenTransaction != null) {
            existingOpenTransaction.addChange(changeRow)
        } else {
            val newTransaction = Transaction({ this.createQueue(it) }, conn!!, changeRow, schemaService)
            openTransactions[changeRow.transaction] = newTransaction
            if (minOpenTransaction == null) {
                minOpenTransaction = newTransaction
            }
        }
    }

    fun clear() {
        this.lastCommittedTransaction?.close()
        this.openTransactions.values.forEach { it.close() }
    }

    private fun createQueue(xid: String): ChronicleQueue {
        return ChronicleQueue.singleBuilder(baseDir.resolve(xid)).build()
    }

    private fun refreshMinOpenScn() {
        minOpenTransaction = openTransactions.values.minByOrNull { it.minScn }
    }
}