create table if not exists robot_command (
  id bigserial primary key,
  command_id uuid not null,
  request_hash char(64) not null,
  robot_id varchar(80) not null,
  target_mode varchar(16) not null check (target_mode in ('mock', 'real')),
  status varchar(32) not null,
  request_json jsonb not null,
  response_json jsonb not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists robot_command_idempotency on robot_command(command_id);
create index if not exists robot_command_robot_time on robot_command(robot_id, created_at desc, id desc);
