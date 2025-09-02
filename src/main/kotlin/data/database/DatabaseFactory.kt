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
        val user = System.getenv("DB_USER") ?: "postgress"
        val password = System.getenv("DB_PASSWORD") ?: "postgress"

        val database = Database.connect(createHikariDataSource(jdbcURL, driverClassName, user, password))

        transaction(database) {
            SchemaUtils.create(Notes)

            // Создаем полнотекстовый индекс
            exec("""
                CREATE EXTENSION IF NOT EXISTS pg_trgm;
                
                ALTER TABLE notes ADD COLUMN IF NOT EXISTS search_vector tsvector;
                
                CREATE INDEX IF NOT EXISTS idx_notes_search 
                ON notes USING GIN(search_vector);
                
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
            """.trimIndent())
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