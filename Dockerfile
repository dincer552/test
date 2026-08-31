FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

COPY src/main/java/web/WebApplication.java src/main/java/web/WebApplication.java

RUN mkdir -p classes && \
    javac -d classes src/main/java/web/WebApplication.java


FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/classes /app/classes

ENV PORT=10000

EXPOSE 10000

CMD ["java", "-cp", "/app/classes", "web.WebApplication"]