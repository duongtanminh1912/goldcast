# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve in their own layer, so editing source does not re-download Maven Central.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- run ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# tzdata so Asia/Ho_Chi_Minh resolves; the alpine image ships without it.
RUN apk add --no-cache tzdata \
    && addgroup -S app \
    && adduser -S -G app app

COPY --from=build /build/target/*.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

# MaxRAMPercentage lets the JVM size its heap from the container limit rather than
# from the host's memory, which is what makes this behave under a small memory cap.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom -jar /app/app.jar"]
