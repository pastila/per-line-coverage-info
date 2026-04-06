FROM php:8.2

# Install system dependencies and useful tools
RUN apt-get update && apt-get install -y \
    git \
    unzip \
    nano \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install Composer
RUN curl -sS https://getcomposer.org/installer | php -- --install-dir=/usr/local/bin --filename=composer


# Install Xdebug
RUN pecl install xdebug \
    && docker-php-ext-enable xdebug

# Install pcov for code coverage
RUN git clone https://github.com/krakjoe/pcov.git \
    && cd pcov \
    && phpize \
    && ./configure --enable-pcov \
    && make \
    && make test \
    && make install \
    && docker-php-ext-enable pcov \
    && cd .. \
    && rm -rf pcov

# Copy custom PHP configuration
COPY .docker/php.ini /usr/local/etc/php/php.ini

# Create log directories
RUN mkdir -p /var/log/php && chown -R www-data:www-data /var/log/php

# Set working directory
WORKDIR /app

# Create a non-root user with same UID/GID as host user
ARG UID
ARG GID
# Validate and create/update appuser with provided UID/GID (no silent defaults)
RUN set -eux; \
    if [ -z "${UID:-}" ] || [ -z "${GID:-}" ]; then echo "ERROR: Build args UID and GID are required (passed by dude/compose)"; exit 2; fi; \
    case "${UID}" in ''|*[!0-9]*) echo "ERROR: UID must be numeric"; exit 2;; esac; \
    case "${GID}" in ''|*[!0-9]*) echo "ERROR: GID must be numeric"; exit 2;; esac; \
    groupadd -g "${GID}" appuser 2>/dev/null || groupmod -g "${GID}" appuser; \
    useradd -u "${UID}" -g "${GID}" -m appuser 2>/dev/null || usermod -u "${UID}" -g "${GID}" appuser

# Change ownership of working directory, logs, and FPM runtime directory
RUN mkdir -p /run/php && chown -R ${UID}:${GID} /app /var/log/php /run/php

# Switch to the non-root user
USER appuser
