# Use a lightweight OpenJDK image
FROM openjdk:17-slim

# Set working directory inside container
WORKDIR /usr/src/app

# Set environment variables for proper output handling
ENV JAVA_OPTS="-Dfile.encoding=UTF-8"
ENV PYTHONUNBUFFERED=1

# Ensure proper buffering behavior for Java programs
ENV _JAVA_OPTIONS="-Djava.io.tmpdir=/tmp -Dfile.encoding=UTF-8"

# Just keep it minimal; no extra files needed
