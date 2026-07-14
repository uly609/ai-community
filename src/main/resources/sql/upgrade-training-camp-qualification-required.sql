ALTER TABLE training_camp
    ADD COLUMN qualification_required TINYINT(1) NOT NULL DEFAULT 0 AFTER stock;
