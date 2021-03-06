#!/usr/bin/env bash

openssl req -newkey rsa:2048 -new -nodes -x509 -days 3650 -out cert-backend.pem -keyout cert-backend-key.pem -subj "/CN=localhost"
openssl req -newkey rsa:2048 -new -nodes -x509 -days 3650 -out cert-frontend.pem -keyout cert-frontend-key.pem -subj "/CN=mtls.oto.tools"