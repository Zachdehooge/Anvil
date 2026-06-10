mvn clean package
cp .env ./target/
cd ./target
java -Denv.path=./.env -jar Anvil-1.0-SNAPSHOT.jar