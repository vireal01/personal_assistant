package com.vireal.api.data.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.cdimascio.dotenv.dotenv
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection
import java.time.Instant

object DatabaseFactory {
    private val dotenv = dotenv {
        ignoreIfMissing = true
        systemProperties = true
    }

    fun init(config: ApplicationConfig) {
        // Получаем настройки БД из .env или системных переменных
        val driverClassName = "org.postgresql.Driver"
        val jdbcURL = dotenv["DATABASE_URL"]
            ?: "jdbc:postgresql://localhost:5432/knowledge_base"
        val user = dotenv["DB_USER"] ?: "postgres"
        val password = dotenv["DB_PASSWORD"] ?: "postgres"

        // Опциональные настройки пула соединений
        val maxPoolSize = dotenv["DB_MAX_POOL_SIZE"]?.toIntOrNull() ?: 10
        val connectionTimeout = dotenv["DB_CONNECTION_TIMEOUT"]?.toLongOrNull() ?: 30000L

        val database = Database.connect(
            createHikariDataSource(
                url = jdbcURL,
                driver = driverClassName,
                user = user,
                password = password,
                maxPoolSize = maxPoolSize,
                connectionTimeout = connectionTimeout
            )
        )

        transaction(database) {
            // Создаем таблицы если их нет
            SchemaUtils.create(Notes, SchemaMigrations)

            // Применяем миграции после создания базовой структуры
            applyMigrations()
        }
    }

    private fun applyMigrations() {
        // Получаем примененные миграции
        val appliedMigrations = SchemaMigrations
            .selectAll()
            .map { it[SchemaMigrations.version] }
            .toSet()

        // Миграция 1: Изменение типов колонок для PostgreSQL специфичных типов
        if (!appliedMigrations.contains(1)) {
            println("Applying migration 1: PostgreSQL specific column types...")

            try {
                val connection = TransactionManager.current().connection.connection as Connection

                // Изменяем тип колонки tags на TEXT[] если нужно
                connection.createStatement().use { statement ->
                    // Проверяем текущий тип колонки
                    val checkSql = """
                        SELECT data_type 
                        FROM information_schema.columns 
                        WHERE table_name = 'notes' AND column_name = 'tags'
                    """.trimIndent()

                    val rs = statement.executeQuery(checkSql)
                    if (rs.next()) {
                        val currentType = rs.getString("data_type")
                        if (currentType == "text") {
                            println("Column 'tags' is TEXT, keeping as is for JSON storage")
                        } else if (currentType != "ARRAY") {
                            // Если это не массив и не текст, конвертируем в текст
                            statement.execute("ALTER TABLE notes ALTER COLUMN tags TYPE TEXT USING tags::TEXT")
                        }
                    }

                    // Аналогично для metadata
                    val checkMetaSql = """
                        SELECT data_type 
                        FROM information_schema.columns 
                        WHERE table_name = 'notes' AND column_name = 'metadata'
                    """.trimIndent()

                    val rsMeta = statement.executeQuery(checkMetaSql)
                    if (rsMeta.next()) {
                        val currentType = rsMeta.getString("data_type")
                        if (currentType == "text") {
                            println("Column 'metadata' is TEXT, keeping as is for JSON storage")
                        } else if (currentType == "jsonb") {
                            // Если это JSONB, можем оставить как есть или конвертировать в TEXT
                            println("Column 'metadata' is JSONB, keeping as is")
                        }
                    }
                }

                // Создаем индексы для оптимизации
                connection.createStatement().use { statement ->
                    // Индекс для user_id и category
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_notes_user_category ON notes(user_id, category)")

                    // Индекс для user_id и created_at
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_notes_user_created ON notes(user_id, created_at DESC)")

                    // GIN индекс для полнотекстового поиска по content
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_notes_content_gin ON notes USING gin(to_tsvector('english', content))")
                }

                SchemaMigrations.insert {
                    it[version] = 1
                    it[appliedAt] = Instant.now()
                }
                println("Migration 1 applied successfully")
            } catch (e: Exception) {
                println("Error applying migration 1: ${e.message}")
            }
        }

        // Миграция 2: Добавление поддержки pgvector для embeddings
        if (!appliedMigrations.contains(2)) {
            println("Applying migration 2: pgvector support...")

            try {
                val connection = TransactionManager.current().connection.connection as Connection

                connection.createStatement().use { statement ->
                    // Проверяем, установлен ли pgvector
                    try {
                        statement.execute("CREATE EXTENSION IF NOT EXISTS vector")
                        println("pgvector extension enabled")

                        // Проверяем тип колонки embedding
                        val checkSql = """
                            SELECT data_type 
                            FROM information_schema.columns 
                            WHERE table_name = 'notes' AND column_name = 'embedding'
                        """.trimIndent()

                        val rs = statement.executeQuery(checkSql)
                        if (rs.next()) {
                            val currentType = rs.getString("data_type")
                            if (currentType == "text") {
                                println("Column 'embedding' is TEXT, will be used for JSON array storage")
                                // Оставляем как TEXT для хранения JSON массива
                            } else if (currentType == "USER-DEFINED") {
                                // Вероятно это уже vector тип
                                println("Column 'embedding' seems to be vector type already")

                                // Создаем HNSW индекс если его нет
                                statement.execute("""
                                    CREATE INDEX IF NOT EXISTS idx_notes_embedding_hnsw 
                                    ON notes USING hnsw (embedding::vector vector_cosine_ops)
                                    WITH (m = 16, ef_construction = 64)
                                """)
                            }
                        }
                    } catch (e: Exception) {
                        println("pgvector not available, embedding will be stored as TEXT: ${e.message}")
                    }
                }

                SchemaMigrations.insert {
                    it[version] = 2
                    it[appliedAt] = Instant.now()
                }
                println("Migration 2 applied successfully")
            } catch (e: Exception) {
                println("Error applying migration 2: ${e.message}")
            }
        }

        // Миграция 3: Добавление дополнительных индексов для производительности
        if (!appliedMigrations.contains(3)) {
            println("Applying migration 3: Performance indexes...")

            try {
                val connection = TransactionManager.current().connection.connection as Connection

                connection.createStatement().use { statement ->
                    // Составной индекс для фильтрации по user_id и поиску в tags
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_notes_user_tags ON notes(user_id, tags)")

                    // Частичный индекс для заметок с embedding
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_notes_with_embedding ON notes(user_id) WHERE embedding IS NOT NULL")

                    // Индекс для сортировки по created_at
                    statement.execute("CREATE INDEX IF NOT EXISTS idx_notes_created_desc ON notes(created_at DESC)")
                }

                SchemaMigrations.insert {
                    it[version] = 3
                    it[appliedAt] = Instant.now()
                }
                println("Migration 3 applied successfully")
            } catch (e: Exception) {
                println("Error applying migration 3: ${e.message}")
            }
        }

        println("All migrations processed")
    }

    private fun createHikariDataSource(
        url: String,
        driver: String,
        user: String,
        password: String,
        maxPoolSize: Int = 10,
        connectionTimeout: Long = 30000L
    ) = HikariDataSource(HikariConfig().apply {
        driverClassName = driver
        jdbcUrl = url
        username = user
        this.password = password
        maximumPoolSize = maxPoolSize
        this.connectionTimeout = connectionTimeout
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"

        dotenv["DB_LEAK_DETECTION_THRESHOLD"]?.toLongOrNull()?.let {
            leakDetectionThreshold = it
        }

        validate()
    })

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}