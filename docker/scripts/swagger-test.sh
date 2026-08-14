# Test all swagger endpoints
echo "Testing Swagger endpoints..."

echo -n "Swagger UI:     "; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/swagger-ui/index.html
echo -n "OpenAPI JSON:   "; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/v3/api-docs
echo -n "OpenAPI YAML:   "; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/v3/api-docs.yaml
echo -n "Swagger Config: "; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/v3/api-docs/swagger-config
echo -n "Health Ping:    "; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/health/ping
echo -n "Health Check:   "; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/health