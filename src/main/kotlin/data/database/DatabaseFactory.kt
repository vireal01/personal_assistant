package com.vireal.data.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {
    fun init(config: ApplicationConfig) {
        val driverClassName = "org.postgresql.Driver"
        val jdbcURL = System.getenv("DATABASE_URL")
            ?: "jdbc:postgresql://localhost:5432/knowledge_base"
        val user = System.getenv("DB_USER") ?: "postgres"
        val password = System.getenv("DB_PASSWORD") ?: "postgres"

        val database = Database.connect(createHikariDataSource(jdbcURL, driverClassName, user, password))

        transaction(database) {
            // Создаем таблицу
            SchemaUtils.create(Notes)

            // Миграции выполняем по отдельности с обработкой ошибок
            try {
                exec("CREATE EXTENSION IF NOT EXISTS pg_trgm")
                println("pg_trgm extension created or already exists")
            } catch (e: Exception) {
                println("Warning: Could not create pg_trgm extension: ${e.message}")
            }

            try {
                exec("CREATE EXTENSION IF NOT EXISTS vector")
                println("pgvector extension created or already exists")
            } catch (e: Exception) {
                println("Warning: Could not create vector extension: ${e.message}")
            }

            try {
                exec("ALTER TABLE notes ADD COLUMN IF NOT EXISTS embedding vector(1536)")
                println("embedding column added or already exists")
            } catch (e: Exception) {
                println("Warning: Could not add embedding column: ${e.message}")
            }

            try {
                exec("ALTER TABLE notes ADD COLUMN IF NOT EXISTS search_vector tsvector")
                println("search_vector column added or already exists")
            } catch (e: Exception) {
                println("Warning: Could not add search_vector column: ${e.message}")
            }

            // Индексы
            try {
                exec("""
                    CREATE INDEX IF NOT EXISTS idx_notes_embedding 
                    ON notes USING ivfflat (embedding vector_cosine_ops)
                    WITH (lists = 100)
                """)
                println("Vector index created or already exists")
            } catch (e: Exception) {
                println("Warning: Could not create vector index: ${e.message}")
            }

            // Триггеры для full-text search
            try {
                exec("""
                    CREATE OR REPLACE FUNCTION notes_search_vector_update() RETURNS trigger AS $$
                    BEGIN
                        NEW.search_vector := to_tsvector('russian', NEW.content);
                        RETURN NEW;
                    END
                    $$ LANGUAGE plpgsql;
                    
                    DROP TRIGGER IF EXISTS notes_search_vector_trigger ON notes;
                    
                    CREATE TRIGGER notes_search_vector_trigger
                    BEFORE INSERT OR UPDATE ON notes
                    FOR EACH ROW EXECUTE FUNCTION notes_search_vector_update();
                """)
                println("Search trigger created or updated")
            } catch (e: Exception) {
                println("Warning: Could not create search trigger: ${e.message}")
            }
        }
    }

    private fun createHikariDataSource(
        url: String,
        driver: String,
        user: String,
        password: String
    ) = HikariDataSource(HikariConfig().apply {
        driverClassName = driver
        jdbcUrl = url
        username = user
        this.password = password
        maximumPoolSize = 10
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    })

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}