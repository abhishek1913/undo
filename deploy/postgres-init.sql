-- Runs once on first start of the postgres container.
create user undo with password 'undo';
create database undo owner undo;
create user shopfront with password 'shopfront';
create database shopfront owner shopfront;
