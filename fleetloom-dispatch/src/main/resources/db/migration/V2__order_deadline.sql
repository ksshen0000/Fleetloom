-- Optional deadline per order; unfinished orders past it raise an alarm (DSP-10).

ALTER TABLE transport_order ADD COLUMN deadline timestamptz;
