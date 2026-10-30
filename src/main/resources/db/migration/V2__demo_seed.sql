-- Two demo tenants so tenant isolation can be shown.
INSERT INTO tenant (tenant_id, name) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Acme Corp'),
  ('22222222-2222-2222-2222-222222222222', 'Globex Ltd');

INSERT INTO office (tenant_id, office_code) VALUES
  ('11111111-1111-1111-1111-111111111111', 'BLR-01'),
  ('11111111-1111-1111-1111-111111111111', 'BLR-02'),
  ('11111111-1111-1111-1111-111111111111', 'HYD-01'),
  ('22222222-2222-2222-2222-222222222222', 'PUN-01'),
  ('22222222-2222-2222-2222-222222222222', 'BLR-01');

-- 20 seats per tenant on one demo trip.
INSERT INTO seat (seat_id, tenant_id, trip_code)
SELECT gen_random_uuid(), '11111111-1111-1111-1111-111111111111', 'TRIP-A-' || g FROM generate_series(1, 20) g;
INSERT INTO seat (seat_id, tenant_id, trip_code)
SELECT gen_random_uuid(), '22222222-2222-2222-2222-222222222222', 'TRIP-G-' || g FROM generate_series(1, 20) g;
