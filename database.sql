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
    status VARCHAR(20) NOT NULL
);

CREATE TABLE purchase (
    purchase_id INT PRIMARY KEY AUTO_INCREMENT,
    user_id INT NOT NULL,
    product_id INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    purchase_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_purchase_product
        FOREIGN KEY (product_id) REFERENCES product(product_id),
    CONSTRAINT unique_user_product
        UNIQUE (user_id, product_id)
);

-- Demo queries
SELECT * FROM product;
SELECT * FROM purchase;

SELECT COUNT(*) AS successful_purchases
FROM purchase
WHERE status = 'SUCCESS';

SELECT available_quantity
FROM product
WHERE product_id = 1;
