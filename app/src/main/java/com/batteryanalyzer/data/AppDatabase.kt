package com.batteryanalyzer.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.batteryanalyzer.model.BatteryChannel
import com.batteryanalyzer.model.BatteryRecord
import com.batteryanalyzer.model.CustomKeyCode
import com.batteryanalyzer.model.DeviceInfo
import com.batteryanalyzer.model.DeviceKeyMapping
import com.batteryanalyzer.model.TimeDimension
import kotlinx.coroutines.flow.Flow

/**
 * Room 类型转换器 - 处理枚举等自定义类型
 */
class Converters {
    @TypeConverter
    fun fromBatteryChannel(value: BatteryChannel): String = value.name

    @TypeConverter
    fun toBatteryChannel(value: String): BatteryChannel =
        try { BatteryChannel.valueOf(value) } catch (e: Exception) { BatteryChannel.SINGLE }

    @TypeConverter
    fun fromTimeDimension(value: TimeDimension): String = value.name

    @TypeConverter
    fun toTimeDimension(value: String): TimeDimension =
        try { TimeDimension.valueOf(value) } catch (e: Exception) { TimeDimension.MINUTES }
}

/**
 * 设备 DAO
 */
@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY lastSeenTime DESC")
    fun getAllDevices(): Flow<List<DeviceInfo>>

    @Query("SELECT * FROM devices ORDER BY lastSeenTime DESC")
    suspend fun getAllDevicesList(): List<DeviceInfo>

    @Query("SELECT * FROM devices WHERE macAddress = :macAddress LIMIT 1")
    suspend fun getDeviceByMac(macAddress: String): DeviceInfo?

    @Query("SELECT * FROM devices WHERE id = :id LIMIT 1")
    suspend fun getDeviceById(id: Long): DeviceInfo?

    /** v7: 可观察的设备单行查询（设备信息/容量变更后实时刷新 UI） */
    @Query("SELECT * FROM devices WHERE id = :id LIMIT 1")
    fun observeDeviceById(id: Long): Flow<DeviceInfo?>

    @Query("SELECT * FROM devices WHERE isConnected = 1 LIMIT 1")
    suspend fun getConnectedDevice(): DeviceInfo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: DeviceInfo): Long

    @Update
    suspend fun updateDevice(device: DeviceInfo)

    @Query("UPDATE devices SET isConnected = :connected, lastSeenTime = :time WHERE id = :id")
    suspend fun updateConnectionStatus(id: Long, connected: Boolean, time: Long = System.currentTimeMillis())

    @Query("UPDATE devices SET isConnected = 0")
    suspend fun resetAllConnectionStatus()

    @Query("DELETE FROM devices WHERE id = :id")
    suspend fun deleteDevice(id: Long)

    @Query("UPDATE devices SET remark = :remark WHERE id = :id")
    suspend fun updateRemark(id: Long, remark: String)

    /** v7: 用户手动修改电池容量（同时标记 isCapacityUserSet，避免被自动识别覆盖） */
    @Query("UPDATE devices SET batteryCapacityMah = :capacity, isCapacityUserSet = 1 WHERE id = :id")
    suspend fun updateDeviceCapacity(id: Long, capacity: Int)

    /** v8: 标记/取消标记手写笔 */
    @Query("UPDATE devices SET isHandwritingPen = :isPen WHERE id = :id")
    suspend fun updateHandwritingPen(id: Long, isPen: Boolean)

    /** v8: 写入 3 个月滚动压缩后的健康度快照与时间 */
    @Query("UPDATE devices SET historicalHealthPercent = :health, lastCompactionTime = :time WHERE id = :id")
    suspend fun updateHistoricalHealth(id: Long, health: Float, time: Long)

    @Query("UPDATE devices SET serialNumber = :serialNumber WHERE macAddress = :macAddress")
    suspend fun updateSerialNumber(macAddress: String, serialNumber: String)

    /** v4.1: 连接后更新设备真实名称（仅当旧名称为"未知设备"时，防止覆盖用户已设置的有效名称） */
    @Query("UPDATE devices SET name = :name WHERE macAddress = :macAddress AND (name = '未知设备' OR name = '' OR name IS NULL OR name GLOB '[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]')")
    suspend fun updateDeviceNameIfUnknown(macAddress: String, name: String)

    /** v4.2: 连接后同步更新真实名称 + 电池容量（仅当旧名称为"未知设备"时） */
    @Query("UPDATE devices SET name = :name, batteryCapacityMah = :capacity WHERE macAddress = :macAddress AND (name = '未知设备' OR name = '' OR name IS NULL OR name GLOB '[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]')")
    suspend fun updateDeviceNameAndCapacityIfUnknown(macAddress: String, name: String, capacity: Int)

    /** v5.0: 获取所有设备（含输入设备），用于键值映射设备选择 */
    @Query("SELECT * FROM devices WHERE deviceType = 'BLE' OR deviceType = 'INPUT_DEVICE' ORDER BY deviceType ASC, lastSeenTime DESC")
    fun getAllDevicesForKeyMapping(): Flow<List<DeviceInfo>>

    /** v5.0: 获取所有输入设备 */
    @Query("SELECT * FROM devices WHERE deviceType = 'INPUT_DEVICE' ORDER BY lastSeenTime DESC")
    suspend fun getInputDevices(): List<DeviceInfo>

    /** v5.0: 按虚拟MAC查找输入设备 */
    @Query("SELECT * FROM devices WHERE macAddress = :macAddress LIMIT 1")
    suspend fun getInputDeviceByVirtualMac(macAddress: String): DeviceInfo?
}

/**
 * 电量记录 DAO - 支持多通道筛选
 */
@Dao
interface BatteryRecordDao {
    @Insert
    suspend fun insertRecord(record: BatteryRecord)

    /** 真正的批量插入 —— Room 原生支持 List 参数，一次事务完成，大幅减少磁盘 I/O */
    @Insert
    suspend fun insertRecords(records: List<BatteryRecord>)

    @Query("SELECT * FROM battery_records WHERE deviceId = :deviceId ORDER BY timestamp DESC")
    fun getRecordsByDevice(deviceId: Long): Flow<List<BatteryRecord>>

    @Query("SELECT * FROM battery_records WHERE deviceId = :deviceId AND channel = :channel ORDER BY timestamp DESC")
    fun getRecordsByDeviceAndChannel(deviceId: Long, channel: String): Flow<List<BatteryRecord>>

    @Query("SELECT * FROM battery_records WHERE deviceId = :deviceId AND timestamp BETWEEN :start AND :end ORDER BY timestamp ASC")
    suspend fun getRecordsInRange(deviceId: Long, start: Long, end: Long): List<BatteryRecord>

    @Query("""
        SELECT * FROM battery_records 
        WHERE deviceId = :deviceId AND timestamp BETWEEN :start AND :end 
        AND channel = :channel
        ORDER BY timestamp ASC
    """)
    suspend fun getRecordsInRangeByChannel(deviceId: Long, start: Long, end: Long, channel: String): List<BatteryRecord>

    @Query("""
        SELECT * FROM battery_records 
        WHERE deviceId = :deviceId AND timestamp BETWEEN :start AND :end 
        ORDER BY timestamp ASC
    """)
    fun getRecordsInRangeFlow(deviceId: Long, start: Long, end: Long): Flow<List<BatteryRecord>>

    @Query("SELECT * FROM battery_records WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRecord(deviceId: Long): BatteryRecord?

    @Query("SELECT * FROM battery_records WHERE deviceId = :deviceId AND channel = :channel ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRecordByChannel(deviceId: Long, channel: String): BatteryRecord?

    @Query("SELECT * FROM battery_records WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLatestRecords(deviceId: Long, limit: Int): List<BatteryRecord>

    @Query("""
        SELECT * FROM battery_records 
        WHERE deviceId = :deviceId 
        AND batteryLevel = (SELECT MAX(batteryLevel) FROM battery_records WHERE deviceId = :deviceId)
        ORDER BY timestamp DESC LIMIT 1
    """)
    suspend fun getMaxBatteryRecord(deviceId: Long): BatteryRecord?

    @Query("""
        SELECT * FROM battery_records 
        WHERE deviceId = :deviceId 
        AND batteryLevel = (SELECT MIN(batteryLevel) FROM battery_records WHERE deviceId = :deviceId AND batteryLevel > 0)
        ORDER BY timestamp DESC LIMIT 1
    """)
    suspend fun getMinBatteryRecord(deviceId: Long): BatteryRecord?

    @Query("SELECT COUNT(*) FROM battery_records WHERE deviceId = :deviceId")
    suspend fun getRecordCount(deviceId: Long): Int

    @Query("SELECT * FROM battery_records WHERE deviceId = :deviceId ORDER BY timestamp ASC")
    suspend fun getAllRecords(deviceId: Long): List<BatteryRecord>

    @Query("SELECT COALESCE(MIN(timestamp), 0) AS minTime, COALESCE(MAX(timestamp), 0) AS maxTime FROM battery_records WHERE deviceId = :deviceId")
    suspend fun getDataTimeRange(deviceId: Long): TimeRangeResult

    @Query("SELECT DISTINCT channel FROM battery_records WHERE deviceId = :deviceId")
    suspend fun getAvailableChannels(deviceId: Long): List<String>

    @Query("DELETE FROM battery_records WHERE deviceId = :deviceId")
    suspend fun deleteAllByDevice(deviceId: Long)

    /** v8: 删除早于指定时间戳（滚动压缩用，仅保留近 N 天原始采样） */
    @Query("DELETE FROM battery_records WHERE deviceId = :deviceId AND timestamp < :cutoff")
    suspend fun deleteRecordsBefore(deviceId: Long, cutoff: Long)

    // ============ 时间维度聚合查询 ============

    /**
     * 按分钟聚合：取每分钟内的平均值/最小值/最大值
     */
    @Query("""
        SELECT 
            (timestamp / 60000) * 60000 AS periodStart,
            AVG(batteryLevel) AS avgLevel,
            MIN(batteryLevel) AS minLevel,
            MAX(batteryLevel) AS maxLevel,
            CAST(SUM(CASE WHEN isCharging = 1 THEN 1 ELSE 0 END) AS FLOAT) / COUNT(*) AS chargingRatio,
            COUNT(*) AS recordCount
        FROM battery_records 
        WHERE deviceId = :deviceId AND timestamp BETWEEN :start AND :end
        GROUP BY periodStart
        ORDER BY periodStart ASC
    """)
    suspend fun getMinuteAggregation(
        deviceId: Long, start: Long, end: Long
    ): List<AggregationResult>

    /**
     * 按天聚合
     */
    @Query("""
        SELECT 
            (timestamp / 86400000) * 86400000 AS periodStart,
            AVG(batteryLevel) AS avgLevel,
            MIN(batteryLevel) AS minLevel,
            MAX(batteryLevel) AS maxLevel,
            CAST(SUM(CASE WHEN isCharging = 1 THEN 1 ELSE 0 END) AS FLOAT) / COUNT(*) AS chargingRatio,
            COUNT(*) AS recordCount
        FROM battery_records 
        WHERE deviceId = :deviceId AND timestamp BETWEEN :start AND :end
        GROUP BY periodStart
        ORDER BY periodStart ASC
    """)
    suspend fun getDayAggregation(
        deviceId: Long, start: Long, end: Long
    ): List<AggregationResult>

    /**
     * 按月聚合
     */
    @Query("""
        SELECT 
            (timestamp / 86400000) * 86400000 AS periodStart,
            AVG(batteryLevel) AS avgLevel,
            MIN(batteryLevel) AS minLevel,
            MAX(batteryLevel) AS maxLevel,
            CAST(SUM(CASE WHEN isCharging = 1 THEN 1 ELSE 0 END) AS FLOAT) / COUNT(*) AS chargingRatio,
            COUNT(*) AS recordCount
        FROM battery_records 
        WHERE deviceId = :deviceId AND timestamp BETWEEN :start AND :end
        GROUP BY strftime('%Y-%m', datetime(timestamp / 1000, 'unixepoch', 'localtime'))
        ORDER BY periodStart ASC
    """)
    suspend fun getMonthAggregation(
        deviceId: Long, start: Long, end: Long
    ): List<AggregationResult>

    /**
     * 按年聚合
     */
    @Query("""
        SELECT 
            (timestamp / 86400000) * 86400000 AS periodStart,
            AVG(batteryLevel) AS avgLevel,
            MIN(batteryLevel) AS minLevel,
            MAX(batteryLevel) AS maxLevel,
            CAST(SUM(CASE WHEN isCharging = 1 THEN 1 ELSE 0 END) AS FLOAT) / COUNT(*) AS chargingRatio,
            COUNT(*) AS recordCount
        FROM battery_records 
        WHERE deviceId = :deviceId AND timestamp BETWEEN :start AND :end
        GROUP BY strftime('%Y', datetime(timestamp / 1000, 'unixepoch', 'localtime'))
        ORDER BY periodStart ASC
    """)
    suspend fun getYearAggregation(
        deviceId: Long, start: Long, end: Long
    ): List<AggregationResult>

    // ============ 聚合缓存表操作 ============

    @Query("SELECT * FROM aggregated_cache WHERE deviceId = :deviceId AND dimension = :dimension AND periodStart BETWEEN :start AND :end ORDER BY periodStart ASC")
    suspend fun getAggregatedCache(
        deviceId: Long, dimension: String, start: Long, end: Long
    ): List<AggregatedRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAggregatedCache(records: List<AggregatedRecordEntity>)

    @Query("DELETE FROM aggregated_cache WHERE deviceId = :deviceId AND dimension = :dimension")
    suspend fun deleteAggregatedCache(deviceId: Long, dimension: String)

    /**
     * 原子性替换聚合缓存：先删除旧数据再插入新数据，由 Room @Transaction 保证事务一致性。
     * 若崩溃发生在方法执行期间，数据库自动回滚，不会出现缓存被清空但新数据未写入的情况。
     */
    @Transaction
    suspend fun replaceAggregatedCache(deviceId: Long, dimension: String, records: List<AggregatedRecordEntity>) {
        deleteAggregatedCache(deviceId, dimension)
        insertAggregatedCache(records)
    }

    @Query("""
        SELECT MAX(recordCount) FROM (
            SELECT MAX(timestamp) as recordCount FROM battery_records WHERE deviceId = :deviceId
            UNION ALL
            SELECT MAX(lastComputed) FROM aggregated_cache WHERE deviceId = :deviceId AND dimension = :dimension
        )
        WHERE recordCount IS NOT NULL
    """)
    suspend fun getDataFreshnessMarker(deviceId: Long, dimension: String): Long?
}

/**
 * 聚合查询结果 - 非实体类，仅用于查询映射
 */
data class AggregationResult(
    val periodStart: Long,
    val avgLevel: Double,
    val minLevel: Int,
    val maxLevel: Int,
    val chargingRatio: Double,
    val recordCount: Int
)

/**
 * 时间范围查询结果
 */
data class TimeRangeResult(
    val minTime: Long,
    val maxTime: Long
)

/**
 * 聚合缓存实体 — 持久化保存各时间维度的聚合结果
 * 当原始数据更新时（新记录写入），缓存自动失效
 */
@Entity(
    tableName = "aggregated_cache",
    primaryKeys = ["deviceId", "dimension", "periodStart"]
)
data class AggregatedRecordEntity(
    val deviceId: Long,
    val dimension: String,       // TimeDimension.name
    val periodStart: Long,       // 该聚合时段的起始时间戳
    val avgLevel: Float,
    val minLevel: Int,
    val maxLevel: Int,
    val chargingRatio: Float,
    val recordCount: Int,
    val lastComputed: Long = System.currentTimeMillis()  // 缓存计算时间
)

/**
 * 设备键值映射 DAO - v6.0: 支持每设备多条映射
 */
@Dao
interface DeviceKeyMappingDao {
    @Query("SELECT * FROM device_key_mappings WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DeviceKeyMapping?

    @Query("SELECT * FROM device_key_mappings WHERE deviceId = :deviceId ORDER BY id ASC")
    suspend fun getByDeviceId(deviceId: Long): List<DeviceKeyMapping>

    @Query("SELECT * FROM device_key_mappings WHERE deviceId = :deviceId ORDER BY id ASC")
    fun getByDeviceIdFlow(deviceId: Long): Flow<List<DeviceKeyMapping>>

    /** 获取设备所有已启用的映射（供无障碍服务拦截用） */
    @Query("SELECT * FROM device_key_mappings WHERE deviceId = :deviceId AND isEnabled = 1 ORDER BY id ASC")
    suspend fun getEnabledMappings(deviceId: Long): List<DeviceKeyMapping>

    @Insert
    suspend fun insert(mapping: DeviceKeyMapping): Long

    @Update
    suspend fun update(mapping: DeviceKeyMapping)

    @Query("DELETE FROM device_key_mappings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM device_key_mappings WHERE deviceId = :deviceId")
    suspend fun deleteByDeviceId(deviceId: Long)

    @Query("UPDATE device_key_mappings SET isEnabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateEnabledById(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE device_key_mappings SET isEnabled = :enabled, updatedAt = :updatedAt WHERE deviceId = :deviceId")
    suspend fun updateEnabledByDeviceId(deviceId: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE device_key_mappings SET sourceKeyCode = :code, sourceLabel = :label, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSourceById(id: Long, code: Int, label: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE device_key_mappings SET targetKeyCode = :code, targetLabel = :label, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTargetById(id: Long, code: Int, label: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM device_key_mappings WHERE deviceId = :deviceId")
    suspend fun countByDeviceId(deviceId: Long): Int
}

/**
 * 自定义键值字典 DAO - v4.0 新增
 */
@Dao
interface CustomKeyCodeDao {
    @Query("SELECT * FROM custom_key_codes WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: Int): CustomKeyCode?

    @Query("SELECT * FROM custom_key_codes ORDER BY code ASC")
    suspend fun getAll(): List<CustomKeyCode>

    @Query("SELECT * FROM custom_key_codes ORDER BY code ASC")
    fun getAllFlow(): Flow<List<CustomKeyCode>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(keyCode: CustomKeyCode): Long

    @Query("DELETE FROM custom_key_codes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM custom_key_codes")
    suspend fun count(): Int
}

/**
 * App 数据库 - v4: 添加设备键值映射表和自定义键值字典表
 *
 * 迁移策略：
 * - 每次 Schema 变更前，在此添加 Migration(oldVersion, newVersion) 对象
 * - 完成后将 @Database version 递增，并在 BatteryRepository 中注册新 Migration
 * - 禁止在未提供 Migration 的情况下手动改 version，否则会触发 fallback
 */
@Database(
    entities = [
        DeviceInfo::class, BatteryRecord::class, AggregatedRecordEntity::class,
        DeviceKeyMapping::class, CustomKeyCode::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun batteryRecordDao(): BatteryRecordDao
    abstract fun deviceKeyMappingDao(): DeviceKeyMappingDao
    abstract fun customKeyCodeDao(): CustomKeyCodeDao

    companion object {
        /**
         * v1 → v2：添加 devices 表的 sn/remark 字段与单通道归一化列
         * （历史迁移，新安装用户直接创建 v3 schema，不会执行此处 SQL）
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN serialNumber TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE devices ADD COLUMN remark TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE devices ADD COLUMN batteryCapacityMah INTEGER NOT NULL DEFAULT 80")
            }
        }

        /**
         * v2 → v3：添加聚合缓存表，支持时间维度曲线
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS aggregated_cache (
                        deviceId INTEGER NOT NULL,
                        dimension TEXT NOT NULL,
                        periodStart INTEGER NOT NULL,
                        avgLevel REAL NOT NULL,
                        minLevel INTEGER NOT NULL,
                        maxLevel INTEGER NOT NULL,
                        chargingRatio REAL NOT NULL,
                        recordCount INTEGER NOT NULL,
                        lastComputed INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(deviceId, dimension, periodStart)
                    )
                """.trimIndent())
            }
        }

        /**
         * v3 → v4：添加设备键值映射表和自定义键值字典表
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS device_key_mappings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        deviceId INTEGER NOT NULL,
                        sourceKeyCode INTEGER NOT NULL DEFAULT -1,
                        targetKeyCode INTEGER NOT NULL DEFAULT -1,
                        sourceLabel TEXT NOT NULL DEFAULT '',
                        targetLabel TEXT NOT NULL DEFAULT '',
                        isEnabled INTEGER NOT NULL DEFAULT 0,
                        injectionMethod TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(deviceId) REFERENCES devices(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_device_key_mappings_deviceId ON device_key_mappings(deviceId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS custom_key_codes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        code INTEGER NOT NULL,
                        name TEXT NOT NULL DEFAULT '',
                        category TEXT NOT NULL DEFAULT 'CUSTOM',
                        description TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_custom_key_codes_code ON custom_key_codes(code)")
            }
        }

        /**
         * v4 → v5：添加 deviceType 字段，支持非蓝牙输入设备
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN deviceType TEXT NOT NULL DEFAULT 'BLE'")
            }
        }

        /**
         * v5 → v6：移除 device_key_mappings 的 deviceId 唯一约束，
         * 允许每设备配置多条键值映射
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 删除旧的唯一索引
                db.execSQL("DROP INDEX IF EXISTS index_device_key_mappings_deviceId")
                // 重建非唯一索引（加速按设备查询）
                db.execSQL("CREATE INDEX IF NOT EXISTS index_device_key_mappings_deviceId ON device_key_mappings(deviceId)")
            }
        }

        /**
         * v6 → v7：新增 isCapacityUserSet 字段，支持用户手动设置电池容量后不被自动识别覆盖
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN isCapacityUserSet INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v7 → v8：新增手写笔相关字段
         *  - isHandwritingPen：是否手写笔（按品牌名识别）
         *  - historicalHealthPercent：最近一次压缩时保存的历史健康度快照
         *  - lastCompactionTime：最近一次滚动压缩时间戳（用于 3 个月滚动压缩）
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN isHandwritingPen INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE devices ADD COLUMN historicalHealthPercent REAL NOT NULL DEFAULT 100.0")
                db.execSQL("ALTER TABLE devices ADD COLUMN lastCompactionTime INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 所有已注册的迁移路径，按版本顺序排列 */
        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
        )
    }
}
