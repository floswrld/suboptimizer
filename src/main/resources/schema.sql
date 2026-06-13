
USE dk6sp_subopt;

DROP TRIGGER IF EXISTS trg_trial_reminder;
DROP TRIGGER IF EXISTS trg_check_renewal;
DROP PROCEDURE IF EXISTS sp_monthly_spend;
DROP PROCEDURE IF EXISTS sp_cancel_subscription;
DROP TABLE IF EXISTS notification;
DROP TABLE IF EXISTS subscription;
DROP TABLE IF EXISTS price_tier;
DROP TABLE IF EXISTS service;
DROP TABLE IF EXISTS category;
DROP TABLE IF EXISTS app_user;

CREATE TABLE app_user (
    user_id        INT          NOT NULL AUTO_INCREMENT,
    email          VARCHAR(120) NOT NULL,
    display_name   VARCHAR(80)  NOT NULL,
    monthly_budget DECIMAL(8,2) NOT NULL DEFAULT 0.00,
    notify_by_email BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     DATE         NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (user_id),
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT chk_budget_nonneg CHECK (monthly_budget >= 0)
);

CREATE TABLE category (
    category_id   INT         NOT NULL AUTO_INCREMENT,
    name          VARCHAR(60) NOT NULL,
    description   VARCHAR(200),
    CONSTRAINT pk_category PRIMARY KEY (category_id),
    CONSTRAINT uq_category_name UNIQUE (name)
);

CREATE TABLE service (
    service_id    INT          NOT NULL AUTO_INCREMENT,
    name          VARCHAR(80)  NOT NULL,
    category_id   INT          NOT NULL,
    website       VARCHAR(150),
    CONSTRAINT pk_service PRIMARY KEY (service_id),
    CONSTRAINT uq_service_name UNIQUE (name),
    CONSTRAINT fk_service_category FOREIGN KEY (category_id)
        REFERENCES category (category_id)
        ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE TABLE price_tier (
    tier_id        INT          NOT NULL AUTO_INCREMENT,
    service_id     INT          NOT NULL,
    tier_name      VARCHAR(60)  NOT NULL,
    price          DECIMAL(8,2) NOT NULL,
    billing_period ENUM('MONTHLY','YEARLY') NOT NULL,
    CONSTRAINT pk_price_tier PRIMARY KEY (tier_id),
    CONSTRAINT uq_tier UNIQUE (service_id, tier_name),
    CONSTRAINT fk_tier_service FOREIGN KEY (service_id)
        REFERENCES service (service_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT chk_price_pos CHECK (price > 0)
);

CREATE TABLE subscription (
    subscription_id INT     NOT NULL AUTO_INCREMENT,
    user_id         INT     NOT NULL,
    tier_id         INT     NOT NULL,
    start_date      DATE    NOT NULL,
    renewal_date    DATE    NOT NULL,
    is_trial        BOOLEAN NOT NULL DEFAULT FALSE,
    trial_end_date  DATE,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_subscription PRIMARY KEY (subscription_id),
    CONSTRAINT fk_sub_user FOREIGN KEY (user_id)
        REFERENCES app_user (user_id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT fk_sub_tier FOREIGN KEY (tier_id)
        REFERENCES price_tier (tier_id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT chk_trial CHECK (is_trial = FALSE OR trial_end_date IS NOT NULL)
);

CREATE TABLE notification (
    notification_id INT          NOT NULL AUTO_INCREMENT,
    subscription_id INT          NOT NULL,
    message         VARCHAR(255) NOT NULL,
    created_at      DATETIME     NOT NULL,
    is_read         BOOLEAN      NOT NULL DEFAULT FALSE,
    email_sent      BOOLEAN      NOT NULL DEFAULT FALSE,
    email_sent_at   DATETIME     NULL,
    CONSTRAINT pk_notification PRIMARY KEY (notification_id),
    CONSTRAINT fk_notif_sub FOREIGN KEY (subscription_id)
        REFERENCES subscription (subscription_id)
        ON UPDATE CASCADE ON DELETE CASCADE
);

INSERT INTO app_user (email, display_name, monthly_budget, created_at) VALUES
 ('anna@example.com',  'Anna Becker',  60.00, '2026-01-10'),
 ('ben@example.com',   'Ben Schulz',   40.00, '2026-02-01'),
 ('clara@example.com', 'Clara Vogt',   80.00, '2026-02-15');

INSERT INTO category (name, description) VALUES
 ('Video Streaming', 'Movies and series streaming services'),
 ('Music Streaming',  'Music streaming services'),
 ('Fitness',          'Gyms and fitness apps'),
 ('Software',         'Productivity and developer software');

INSERT INTO service (name, category_id, website) VALUES
 ('Netflix',         1, 'https://netflix.com'),
 ('Disney+',         1, 'https://disneyplus.com'),
 ('Spotify',         2, 'https://spotify.com'),
 ('Apple Music',     2, 'https://music.apple.com'),
 ('FitX Gym',        3, 'https://fitx.de'),
 ('JetBrains Suite', 4, 'https://jetbrains.com');

INSERT INTO price_tier (service_id, tier_name, price, billing_period) VALUES
 (1, 'Standard',   13.99, 'MONTHLY'),
 (1, 'Premium',    19.99, 'MONTHLY'),
 (2, 'Standard',    8.99, 'MONTHLY'),
 (3, 'Individual',  9.99, 'MONTHLY'),
 (4, 'Individual', 10.99, 'MONTHLY'),
 (5, 'Flat',       29.99, 'MONTHLY'),
 (6, 'All Products',289.00,'YEARLY');

INSERT INTO subscription (user_id, tier_id, start_date, renewal_date, is_trial, trial_end_date, active) VALUES
 (1, 1, '2026-01-15', '2026-06-15', FALSE, NULL,         TRUE),
 (1, 4, '2026-01-15', '2026-06-15', FALSE, NULL,         TRUE),
 (1, 5, '2026-03-01', '2026-06-08', TRUE,  '2026-06-04', TRUE),
 (2, 3, '2026-02-05', '2026-06-05', FALSE, NULL,         TRUE),
 (2, 6, '2026-02-05', '2026-06-07', TRUE,  '2026-06-05', TRUE),
 (3, 7, '2026-02-20', '2027-02-20', FALSE, NULL,         TRUE);

DELIMITER //

CREATE TRIGGER trg_trial_reminder
AFTER INSERT ON subscription
FOR EACH ROW
BEGIN
    IF NEW.is_trial = TRUE
       AND NEW.trial_end_date IS NOT NULL
       AND DATEDIFF(NEW.trial_end_date, CURDATE()) BETWEEN 0 AND 3 THEN
        INSERT INTO notification (subscription_id, message, created_at, is_read)
        VALUES (NEW.subscription_id,
                CONCAT('Trial ends on ', NEW.trial_end_date,
                       ' - cancel now to avoid being charged.'),
                NOW(), FALSE);
    END IF;
END//

CREATE TRIGGER trg_check_renewal
BEFORE INSERT ON subscription
FOR EACH ROW
BEGIN
    IF NEW.renewal_date < NEW.start_date THEN
        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'renewal_date must not be before start_date';
    END IF;
END//

CREATE PROCEDURE sp_monthly_spend (IN p_user_id INT, OUT p_total DECIMAL(10,2))
BEGIN
    SELECT COALESCE(SUM(
             CASE pt.billing_period
                  WHEN 'YEARLY' THEN pt.price / 12
                  ELSE pt.price
             END), 0)
    INTO p_total
    FROM subscription s
    JOIN price_tier pt ON s.tier_id = pt.tier_id
    WHERE s.user_id = p_user_id
      AND s.active = TRUE;
END//

CREATE PROCEDURE sp_cancel_subscription (IN p_sub_id INT)
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;
        UPDATE subscription
        SET active = FALSE
        WHERE subscription_id = p_sub_id;

        INSERT INTO notification (subscription_id, message, created_at, is_read)
        VALUES (p_sub_id, 'Subscription was cancelled by the user.',
                NOW(), FALSE);
    COMMIT;
END//

DELIMITER ;
