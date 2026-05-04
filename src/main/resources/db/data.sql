INSERT IGNORE INTO products (id, name, description, price, check_in_date, check_out_date, check_in_time, check_out_time, sale_open_time, status, location)
VALUES (
    1,
    '프리미엄 오션뷰 스위트',
    '탁 트인 오션뷰와 프리미엄 어메니티가 제공되는 특급 스위트룸입니다.',
    150000,
    '2026-05-10',
    '2026-05-11',
    '15:00:00',
    '11:00:00',
    '2026-05-01 00:00:00',
    'ACTIVE',
    '제주도 서귀포시'
);

INSERT IGNORE INTO inventories (id, product_id, total_stock, reserved_stock)
VALUES (1, 1, 10, 0);

INSERT IGNORE INTO users (id, email, name, phone, y_points)
VALUES
    (1, 'alice@example.com', '김앨리스', '010-1111-2222', 100000),
    (2, 'bob@example.com',   '이밥',     '010-3333-4444', 50000),
    (3, 'carol@example.com', '박캐롤',   '010-5555-6666', 200000),
    (4, 'dave@example.com',  '최데이브', '010-7777-8888', 0),
    (5, 'eve@example.com',   '정이브',   '010-9999-0000', 30000);
