.PHONY: help up down logs rebuild clean backend-test backend-run frontend-dev frontend-build seed

help:
	@echo "goldcast — dự báo giá vàng (thế giới + Việt Nam)"
	@echo ""
	@echo "  make up             Khởi động toàn bộ stack (db + backend + frontend)"
	@echo "  make down           Dừng stack"
	@echo "  make logs           Xem log tất cả service"
	@echo "  make rebuild        Build lại image và khởi động"
	@echo "  make clean          Dừng stack và xoá volume database"
	@echo "  make backend-test   Chạy unit test backend"
	@echo "  make backend-run    Chạy backend ở chế độ dev (cần Postgres đang chạy)"
	@echo "  make frontend-dev   Chạy frontend ở chế độ dev"
	@echo "  make seed           Gọi ingest thủ công (backend phải đang chạy)"

up:
	@test -f .env || cp .env.example .env
	docker compose up -d --build
	@echo ""
	@echo "Frontend : http://localhost:3000"
	@echo "API      : http://localhost:8080/api/v1"
	@echo "Swagger  : http://localhost:8080/swagger-ui.html"

down:
	docker compose down

logs:
	docker compose logs -f

rebuild:
	docker compose build --no-cache
	docker compose up -d

clean:
	docker compose down -v

backend-test:
	cd backend && mvn -B test

backend-run:
	cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev

frontend-dev:
	cd frontend && npm install && npm run dev

frontend-build:
	cd frontend && npm install && npm run build

seed:
	curl -X POST http://localhost:8080/api/v1/admin/ingest
