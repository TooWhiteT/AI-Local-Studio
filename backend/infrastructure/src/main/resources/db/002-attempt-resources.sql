ALTER TABLE task_attempts ADD COLUMN peak_vram_bytes INTEGER;
ALTER TABLE task_attempts ADD COLUMN peak_ram_bytes INTEGER;
ALTER TABLE task_attempts ADD COLUMN resource_samples INTEGER NOT NULL DEFAULT 0;
