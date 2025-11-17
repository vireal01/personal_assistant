# Personal Assistant - Knowledge Base API

A Kotlin-based REST API for managing personal notes and providing AI-powered query responses using OpenAI's GPT models. This application serves as a personal knowledge base where users can store notes and ask questions that are answered based on the stored information.

## 🚀 Features

- **Note Management**: Create, retrieve, and search through personal notes
- **MCP Architecture**: Model Context Protocol for unified AI interactions
- **AI-Powered Queries**: Two modes - with and without knowledge base search
- **Hybrid Search**: Vector + full-text search with intelligent ranking
- **Vector Embeddings**: Semantic search using OpenAI embeddings
- **Full-Text Search**: Advanced search capabilities across all notes
- **User Isolation**: Each user's notes are kept separate
- **RESTful API**: Clean and intuitive API endpoints with legacy compatibility
- **Docker Support**: Easy deployment with Docker and Docker Compose
- **PostgreSQL Database**: Robust data persistence with vector and full-text search

## 🛠️ Tech Stack

- **Backend**: Kotlin with Ktor framework
- **Database**: PostgreSQL with Exposed ORM
- **AI Integration**: OpenAI GPT-3.5-turbo
- **Containerization**: Docker & Docker Compose
- **Build Tool**: Gradle
- **Logging**: Logback

## 📋 Prerequisites

- Java 17 or higher
- Docker and Docker Compose (for containerized deployment)
- OpenAI API key (for AI functionality)

## 🚀 Quick Start

### Using Docker Compose (Recommended)

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd presonal_assistant
   ```

2. **Set up environment variables**
   ```bash
   cp .env.example .env
   # Edit .env and add your OpenAI API key
   echo "OPENAI_API_KEY=your_openai_api_key_here" >> .env
   ```

3. **Start the application**
   ```bash
   docker-compose up -d
   ```

The application will be available at `http://localhost:8080`

### Manual Setup

1. **Start PostgreSQL database**
   ```bash
   docker-compose up -d postgres
   ```

2. **Build and run the application**
   ```bash
   ./gradlew buildFatJar
   java -jar build/libs/presonal_assistant-1.0-SNAPSHOT-all.jar
   ```

## 📚 API Documentation

### Base URL
```
http://localhost:8080
```

### MCP API (Recommended)

#### Query with Knowledge Base Search
```http
POST /api/mcp/query/with-context
Content-Type: application/json

{
  "userId": 123,
  "question": "How to setup Docker?",
  "tags": ["docker", "setup"],
  "category": "devops"
}
```

#### Query without Knowledge Base Search
```http
POST /api/mcp/query/without-context
Content-Type: application/json

{
  "question": "Explain this code",
  "context": "function hello() { console.log('Hello World'); }"
}
```

#### Get Available MCP Tools
```http
GET /api/mcp/tools
```

#### Execute MCP Tool
```http
POST /api/mcp/tools/execute
Content-Type: application/json

{
  "name": "query_with_knowledge_base",
  "arguments": {
    "userId": 123,
    "question": "Your question here"
  }
}
```

### Legacy API (Backward Compatible)

#### Query with Knowledge Base
```http
POST /api/query
Content-Type: application/json

{
  "userId": 123,
  "question": "Your question here"
}
```

#### Query without Knowledge Base
```http
POST /api/queryLlm
Content-Type: application/json

{
  "question": "Your question here",
  "extraContext": "Additional context"
}
```

### Notes Management

#### Health Check
```http
GET /health
```


**Create a new note**
```http
POST /api/notes
Content-Type: application/json

{
  "userId": 123,
  "content": "Your note content here"
}
```

**Get all notes for a user**
```http
GET /api/notes/{userId}
```

**Search notes**
```http
GET /api/notes/search?userId={userId}&q={search_query}
```

#### AI Query

**Ask a question**
```http
POST /api/query
Content-Type: application/json

{
  "userId": 123,
  "question": "What did I learn about machine learning?"
}
```

### Example Usage

1. **Add a note:**
   ```bash
   curl -X POST http://localhost:8080/api/notes \
     -H "Content-Type: application/json" \
     -d '{"userId": 1, "content": "Machine learning is a subset of AI that focuses on algorithms that can learn from data."}'
   ```

2. **Ask a question:**
   ```bash
   curl -X POST http://localhost:8080/api/query \
     -H "Content-Type: application/json" \
     -d '{"userId": 1, "question": "What is machine learning?"}'
   ```

## 🏗️ Project Structure

```
src/main/kotlin/
├── Application.kt                 # Main application entry point
├── data/
│   ├── database/
│   │   ├── DatabaseFactory.kt    # Database connection setup
│   │   └── Tables.kt             # Database table definitions
│   ├── models/
│   │   └── Models.kt             # Data models and DTOs
│   └── repository/
│       └── NotesRepository.kt    # Data access layer
├── plugins/
│   ├── CORS.kt                   # CORS configuration
│   ├── Database.kt               # Database plugin setup
│   ├── Routing.kt                # Route configuration
│   ├── Serialization.kt          # JSON serialization setup
│   └── StatusPages.kt            # Error handling
├── routes/
│   ├── HealthRoute.kt            # Health check endpoint
│   ├── NotesRoutes.kt            # Notes management endpoints
│   └── QueryRoutes.kt            # AI query endpoints
└── services/
    ├── LLMService.kt             # OpenAI integration
    ├── NotesService.kt           # Notes business logic
    └── QueryService.kt           # Query processing logic
```

## 🔧 Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | Server port | 8080 |
| `DATABASE_URL` | PostgreSQL connection URL | jdbc:postgresql://localhost:5432/knowledge_base |
| `DB_USER` | Database username | postgres |
| `DB_PASSWORD` | Database password | postgres |
| `OPENAI_API_KEY` | OpenAI API key | Required for AI functionality |

### Database Configuration

The application uses PostgreSQL with the following default settings:
- Database: `knowledge_base`
- Username: `postgres`
- Password: `postgres`
- Port: `5432`

## 🧪 Development

### Building the Project

```bash
# Build the project
./gradlew build

# Build fat JAR
./gradlew buildFatJar

# Run tests
./gradlew test
```

### Running in Development Mode

```bash
# Start database
docker-compose up -d postgres

# Run application
./gradlew run
```

## 🐳 Docker

### Building Docker Image

```bash
docker build -t personal-assistant .
```

### Running with Docker

```bash
docker run -p 8080:8080 \
  -e OPENAI_API_KEY=your_api_key \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:5432/knowledge_base \
  personal-assistant
```

## 📝 Database Schema

The application uses a simple schema with one main table:

```sql
CREATE TABLE notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notes_user_id ON notes(user_id);
CREATE INDEX idx_notes_content_fts ON notes USING gin(to_tsvector('russian', content));
```

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🆘 Support

If you encounter any issues or have questions:

1. Check the [Issues](https://github.com/your-repo/issues) page
2. Create a new issue with detailed information
3. Include logs and steps to reproduce the problem

## 🔮 Future Enhancements

- [ ] User authentication and authorization
- [ ] Note categories and tags
- [ ] File attachments support
- [ ] Advanced search filters
- [ ] Note sharing capabilities
- [ ] Web interface
- [ ] Mobile app
- [ ] Integration with other AI providers
- [ ] Note versioning and history
- [ ] Export/import functionality

---

**Note**: This is a personal assistant application designed for individual use. Make sure to keep your OpenAI API key secure and never commit it to version control.
