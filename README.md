# Spring 4 Demo

Projet de démonstration backend Java/Spring Boot avec PostgreSQL, Docker, Swagger, CI et tests d’intégration.

## Objectif

Projet de remise à niveau sur un socle Spring moderne, avec une API simple de gestion de clients.

## Stack

- Java 21 ou 25 selon branche de travail
- Spring Boot
- Spring Web
- Spring Data JPA
- PostgreSQL
- Maven
- Docker

## Lancement

### Base seule pour le développement local

```bash
./run.sh db
mvn spring-boot:run
```

## Dockerfile

Le projet utilise un Dockerfile multi-stage :
- build Maven dans une image JDK
- exécution dans une image JRE plus légère

## Commandes utiles

### Build image

```bash
./build.sh
```


## Endpoints

### Lister les clients

```bash
curl http://localhost:8080/customers
```
### Ajouter des clients
```bash
curl -X POST http://localhost:8080/customers \
  -H "Content-Type: application/json" \
  -d '{"name":"Benoit","email":"benoit@example.com"}'
  
curl -X POST http://localhost:8080/customers \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","email":"alice@example.com"}'  
```

### Autres
```bash
curl http://localhost:8080/customers/recent
curl http://localhost:8080/customers/aggregate
http://localhost:8080/swagger-ui.html
```