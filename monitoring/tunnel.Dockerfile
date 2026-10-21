ARG PYTHON_IMAGE
FROM ${PYTHON_IMAGE}
RUN apk add --no-cache openssh-client
ENTRYPOINT ["sh", "/config/tunnel.sh"]
