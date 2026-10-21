# Reproducible build (numeral 5.3): the image is built and tested from source
# with the same JDK the CI uses. Tests run during the build; nothing is skipped.
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY document-generation-core ./document-generation-core
COPY document-generation-adapters ./document-generation-adapters
COPY document-generation-app ./document-generation-app
RUN mvn -B package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /workspace/document-generation-app/target/document-generation-api.jar /app/app.jar
USER app:app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
