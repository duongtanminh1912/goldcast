# Cong cu cho ansible/check.sh: ansible-core va ansible-lint ghim phien ban.
ARG PYTHON_IMAGE
FROM ${PYTHON_IMAGE}
ARG ANSIBLE_CORE_VERSION
ARG ANSIBLE_LINT_VERSION
RUN apk add --no-cache openssh-client \
 && pip install --no-cache-dir --root-user-action=ignore \
      "ansible-core==${ANSIBLE_CORE_VERSION}" "ansible-lint==${ANSIBLE_LINT_VERSION}"
