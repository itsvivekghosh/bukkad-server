# ==============================
# FULL CLEAN START
# ==============================

# Step 1: Clean everything
docker compose -f docker-compose.dev.yml down -v --remove-orphans 2>/dev/null
docker volume prune -f 2>/dev/null

# Step 2: Build JAR
mvn clean package -DskipTests

# Step 3: Build Docker image
cd docker/
docker compose -f docker-compose.dev.yml build --no-cache app

# Step 4: Start MySQL + Redis FIRST
docker compose -f docker-compose.dev.yml up -d mysql redis

# Step 5: Wait for MySQL healthy
echo "Waiting for MySQL to be healthy..."
for i in $(seq 1 60); do
    STATUS=$(docker inspect bhukkad-mysql-dev --format='{{.State.Health.Status}}' 2>/dev/null || echo "not_found")
    if [ "$STATUS" = "healthy" ]; then
        echo ""
        echo "MySQL is healthy!"
        break
    fi
    echo "."
    sleep 3
done

# Step 6: Start App
docker compose -f docker-compose.dev.yml up -d app

# Step 7: Start UI tools
docker compose -f docker-compose.dev.yml up -d redis-commander phpmyadmin

# Step 8: Watch app logs
docker logs -f bhukkad-app-dev


# ==============================
# ONE-LINE START (after first setup)
# ==============================
docker compose -f docker-compose.dev.yml up -d && docker logs -f bhukkad-app-dev


# ==============================
# STOP
# ==============================
docker compose -f docker-compose.dev.yml down


# ==============================
# STOP + DELETE ALL DATA
# ==============================
docker compose -f docker-compose.dev.yml down -v --remove-orphans


# ==============================
# REBUILD APP ONLY (after code change)
# ==============================
mvn clean package -DskipTests
docker compose -f docker-compose.dev.yml build --no-cache app
docker compose -f docker-compose.dev.yml up -d app
docker logs -f bhukkad-app-dev


# ==============================
# CHECK STATUS
# ==============================
docker ps
docker compose -f docker-compose.dev.yml ps


# ==============================
# VERIFY
# ==============================
curl -s http://localhost:8080/api/health | python3 -m json.tool
curl -s http://localhost:8080/api/health/ping
curl -s http://localhost:8080/api/health/detailed | python3 -m json.tool
