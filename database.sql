CREATE DATABASE IF NOT EXISTS flash_sale_db;
USE flash_sale_db;

DROP TABLE IF EXISTS purchase;
DROP TABLE IF EXISTS product;

CREATE TABLE product (
    product_id INT PRIMARY KEY AUTO_INCREMENT,
    product_name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    total_quantity INT NOT NULL CHECK (total_quantity >= 0),
    available_quantity INT NOT NULL CHECK (available_quantity >= 0),
    price DECIMAL(10,2) NOT NULL CHECK (price >= 0),
    status VARCHAR(20) NOT NULL,
    original_price DECIMAL(10,2),
    category VARCHAR(30),
    image_url VARCHAR(30)
);

-- only confirmed (paid) orders are saved here, a seat hold lives in the engine for 5 minutes
CREATE TABLE purchase (
    purchase_id INT PRIMARY KEY AUTO_INCREMENT,
    user_id INT NOT NULL,
    product_id INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    purchase_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    pnr VARCHAR(20),
    CONSTRAINT fk_purchase_product
        FOREIGN KEY (product_id) REFERENCES product(product_id),
    CONSTRAINT unique_user_product
        UNIQUE (user_id, product_id)
);

-- Demo queries
SELECT * FROM product;
SELECT * FROM purchase;

SELECT COUNT(*) AS confirmed_orders
FROM purchase
WHERE status = 'CONFIRMED';

SELECT available_quantity
FROM product
WHERE product_id = 1;

-- this is the row lock the engine uses when an order is confirmed (strict 2PL in the database)
-- START TRANSACTION;
-- SELECT available_quantity FROM product WHERE product_id = 1 FOR UPDATE;
-- ... UPDATE / INSERT ...
-- COMMIT;
