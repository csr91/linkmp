FROM python:3.12.0-alpine
MAINTAINER Guido Navalesi <guido@jobint.com>

COPY MP.py /opt/MP.py

WORKDIR /opt
CMD ["/bin/bash", "-c" "python" "/opt/MP.py"]