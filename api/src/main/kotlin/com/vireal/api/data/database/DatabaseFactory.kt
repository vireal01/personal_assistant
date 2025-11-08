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

    // Конфигурация для векторного поиска
    private const val VECTOR_DIMENSION = 1536  // Для OpenAI text-embedding-3-small
    private const val HNSW_M = 16              // Параметр M для HNSW индекса
    private const val HNSW_EF_CONSTRUCTION = 64 // Параметр ef_construction

    fun init(config: ApplicationConfig) {
        val driverClassName = "org.postgresql.Driver"
        val jdbcURL = dotenv["DATABASE_URL"]
            ?: "jdbc:postgresql://localhost:5432/knowledge_base"
        val user = dotenv["DB_USER"] ?: "postgres"
        val password = dotenv["DB_PASSWORD"] ?: "postgres"

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
            SchemaUtils.create(Notes, SchemaMigrations, NoteSearchCache)

            // Применяем миграции
            applyScalableMigrations()
        }
    }

    private fun applyScalableMigrations() {
        val appliedMigrations = SchemaMigrations
            .selectAll()
            .map { it[SchemaMigrations.version] }
            .toSet()

        // Миграция 4: Полная поддержка pgvector для масштабирования
        if (!appliedMigrations.contains(4)) {
            println("Applying migration 4: Full pgvector support for scalability...")

            try {
                val connection = TransactionManager.current().connection.connection as Connection

                connection.createStatement().use { statement ->
                    // 1. Включаем расширение pgvector
                    statement.execute("CREATE EXTENSION IF NOT EXISTS vector")
                    println("✓ pgvector extension enabled")

                    // 2. Проверяем текущий тип колонки embedding
                    val checkSql = """
                        SELECT data_type, udt_name
                        FROM information_schema.columns 
                        WHERE table_name = 'notes' AND column_name = 'embedding'
                    """.trimIndent()

                    val rs = statement.executeQuery(checkSql)
                    if (rs.next()) {
                        val currentType = rs.getString("data_type")
                        val udtName = rs.getString("udt_name")

                        if (currentType != "USER-DEFINED" || udtName != "vector") {
                            println("Converting embedding column from $currentType to vector($VECTOR_DIMENSION)...")

                            // 3. Создаем временную колонку для векторов
                            statement.execute("""
                                ALTER TABLE notes 
                                ADD COLUMN IF NOT EXISTS embedding_vector vector($VECTOR_DIMENSION)
                            """)

                            // 4. Мигрируем существующие embeddings (если они в JSON формате)
                            if (currentType == "text") {
                                println("Migrating existing text embeddings to vector format...")
                                statement.execute("""
                                    UPDATE notes 
                                    SET embedding_vector = embedding::vector
                                    WHERE embedding IS NOT NULL 
                                    AND embedding != '[]'
                                    AND embedding LIKE '[%'
                                """)
                            }

                            // 5. Удаляем старую колонку и переименовываем новую
                            statement.execute("ALTER TABLE notes DROP COLUMN IF EXISTS embedding")
                            statement.execute("ALTER TABLE notes RENAME COLUMN embedding_vector TO embedding")

                            println("✓ Embedding column converted to vector type")
                        } else {
                            println("✓ Embedding column is already vector type")
                        }
                    } else {
                        // Колонки нет, создаем сразу правильного типа
                        statement.execute("""
                            ALTER TABLE notes 
                            ADD COLUMN embedding vector($VECTOR_DIMENSION)
                        """)
                        println("✓ Created embedding column with vector type")
                    }

                    // 6. Создаем HNSW индекс для быстрого поиска
                    statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_notes_embedding_hnsw 
                        ON notes USING hnsw (embedding vector_cosine_ops)
                        WITH (m = $HNSW_M, ef_construction = $HNSW_EF_CONSTRUCTION)
                    """)
                    println("✓ HNSW index created for fast vector search")

                    // 7. Создаем составной индекс для фильтрации
                    statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_notes_user_embedding 
                        ON notes(user_id) 
                        WHERE embedding IS NOT NULL
                    """)
                    println("✓ Composite index for user filtering created")

                    // 8. Создаем индексы для текстового поиска
                    statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_notes_content_trgm 
                        ON notes USING gin (content gin_trgm_ops)
                    """)
                    println("✓ Trigram index for text search created")

                    // 9. Оптимизация для категорий и тегов
                    statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_notes_user_category_created 
                        ON notes(user_id, category, created_at DESC)
                    """)

                    statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_notes_tags_gin 
                        ON notes USING gin (tags)
                    """)
                    println("✓ Indexes for categories and tags created")
                }

                SchemaMigrations.insert {
                    it[version] = 4
                    it[appliedAt] = Instant.now()
                }
                println("✓ Migration 4 applied successfully")

            } catch (e: Exception) {
                println("Error applying migration 4: ${e.message}")
                e.printStackTrace()
            }
        }

        // Миграция 5: Оптимизация производительности для больших объемов
        if (!appliedMigrations.contains(5)) {
            println("Applying migration 5: Performance optimizations...")

            try {
                val connection = TransactionManager.current().connection.connection as Connection

                connection.createStatement().use { statement ->
                    // 1. Включаем расширение для триграммного поиска
                    statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")
                    println("✓ pg_trgm extension enabled")

                    // 2. Создаем партиционирование для очень больших объемов (опционально)
                    // Это пример, активируйте при необходимости
                    /*
                    statement.execute("""
                        -- Создаем партиционированную таблицу
                        CREATE TABLE IF NOT EXISTS notes_partitioned (
                            LIKE notes INCLUDING ALL
                        ) PARTITION BY HASH (user_id)
                    """)

                    // Создаем 10 партиций
                    for (i in 0..9) {
                        statement.execute("""
                            CREATE TABLE IF NOT EXISTS notes_part_$i
                            PARTITION OF notes_partitioned
                            FOR VALUES WITH (modulus 10, remainder $i)
                        """)
                    }
                    */

                    // 3. Настройка параметров для векторного поиска
                    statement.execute("""
                        -- Увеличиваем лимит для hnsw.ef_search для лучшей точности
                        ALTER DATABASE ${connection.catalog} 
                        SET hnsw.ef_search = 100
                    """)
                    println("✓ Vector search parameters optimized")

                    // 4. Создаем функцию для batch-обработки embeddings
                    statement.execute("""
                        CREATE OR REPLACE FUNCTION process_embeddings_batch(
                            batch_size INT DEFAULT 100
                        ) RETURNS INT AS $$
                        DECLARE
                            processed_count INT := 0;
                        BEGIN
                            -- Функция для batch обработки в БД
                            -- Реализация зависит от ваших потребностей
                            RETURN processed_count;
                        END;
                        $$ LANGUAGE plpgsql;
                    """)
                    println("✓ Batch processing function created")

                    // 5. Анализируем таблицу для оптимизатора
                    statement.execute("ANALYZE notes")
                    println("✓ Table statistics updated")
                }

                SchemaMigrations.insert {
                    it[version] = 5
                    it[appliedAt] = Instant.now()
                }
                println("✓ Migration 5 applied successfully")

            } catch (e: Exception) {
                println("Error applying migration 5: ${e.message}")
                // Некритичная ошибка, продолжаем
            }
        }

        // Миграция 6: Добавление кэш-таблицы для частых запросов
        if (!appliedMigrations.contains(6)) {
            println("Applying migration 6: Cache table for frequent queries...")

            try {
                val connection = TransactionManager.current().connection.connection as Connection

                connection.createStatement().use { statement ->
                    // Материализованное представление для топ похожих заметок
                    statement.execute("""
                        CREATE MATERIALIZED VIEW IF NOT EXISTS mv_similar_notes AS
                        SELECT 
                            n1.id as note_id,
                            n2.id as similar_note_id,
                            1 - (n1.embedding <=> n2.embedding) as similarity
                        FROM notes n1
                        JOIN notes n2 ON n1.user_id = n2.user_id AND n1.id != n2.id
                        WHERE n1.embedding IS NOT NULL AND n2.embedding IS NOT NULL
                        AND 1 - (n1.embedding <=> n2.embedding) > 0.7
                    """)

                    statement.execute("""
                        CREATE INDEX IF NOT EXISTS idx_mv_similar_notes 
                        ON mv_similar_notes(note_id, similarity DESC)
                    """)

                    println("✓ Materialized view for similar notes created")
                }

                SchemaMigrations.insert {
                    it[version] = 6
                    it[appliedAt] = Instant.now()
                }
                println("✓ Migration 6 applied successfully")

            } catch (e: Exception) {
                println("Warning: Could not create materialized view: ${e.message}")
                // Не критично, продолжаем
            }
        }

        println("All migrations processed successfully")
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

        // Оптимизации для работы с векторами
        addDataSourceProperty("prepStmtCacheSize", "250")
        addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        addDataSourceProperty("cachePrepStmts", "true")
        addDataSourceProperty("useServerPrepStmts", "true")

        dotenv["DB_LEAK_DETECTION_THRESHOLD"]?.toLongOrNull()?.let {
            leakDetectionThreshold = it
        }

        validate()
    })

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}