FROM gradle:8.13-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle :relay:installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/relay/build/install/relay /app/relay
RUN apt-get update \
	&& apt-get install -y --no-install-recommends curl \
	&& rm -rf /var/lib/apt/lists/*
EXPOSE 8080
CMD ["/app/relay/bin/relay"]
