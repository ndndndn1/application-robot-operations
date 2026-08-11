alter table telemetry_event
    add column event_id uuid,
    add column payload_hash char(64),
    add column payload jsonb,
    add column response_json jsonb;

update telemetry_event
set event_id = gen_random_uuid(),
    payload_hash = repeat('0', 64),
    payload = jsonb_build_object(
        'robotId', robot_id, 'timestamp', extract(epoch from observed_at), 'x', x, 'y', y,
        'battery', battery, 'taskState', 'idle', 'errorCodes', '[]'::jsonb, 'modelId', model_id),
where event_id is null;

update telemetry_event
set response_json = jsonb_build_object(
    'eventId', event_id, 'telemetry', payload, 'severity', severity,
    'rules', to_jsonb(string_to_array(rules, ',')), 'duplicate', false)
where response_json is null;

alter table telemetry_event
    alter column event_id set not null,
    alter column payload_hash set not null,
    alter column payload set not null,
    alter column response_json set not null,
    alter column rules type text[] using string_to_array(rules, ',');

create unique index telemetry_event_idempotency on telemetry_event(event_id);
create index telemetry_robot_order on telemetry_event(robot_id, observed_at desc, id desc);
