# Etapa 1: compilar con Maven (JDK 21)
# Se usa la imagen de Maven en vez de mvnw para evitar problemas de saltos de linea
# de Windows (CRLF) al ejecutar mvnw dentro de Linux.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# Etapa 2: ejecutar solo con JRE 21
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home appuser
COPY --from=build /app/target/*.jar app.jar
USER appuser
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]