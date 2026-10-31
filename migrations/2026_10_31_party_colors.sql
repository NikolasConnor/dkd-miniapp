-- Выполнить один раз в Supabase SQL Editor
ALTER TABLE public.parties
ADD COLUMN IF NOT EXISTS color text NOT NULL DEFAULT '#4169e1';

DO $$ BEGIN
  ALTER TABLE public.parties ADD CONSTRAINT parties_color_hex_check CHECK (color ~ '^#[0-9A-Fa-f]{6}$');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
