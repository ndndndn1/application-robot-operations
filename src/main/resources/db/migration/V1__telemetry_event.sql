create table if not exists telemetry_event (
  id bigserial primary key,
  robot_id varchar(80) not null,
  model_id varchar(40) not null,
  observed_at timestamptz not null,
  x double precision not null,
  y double precision not null,
  battery double precision not null,
  severity varchar(20) not null,
  rules text not null
);
create index if not exists telemetry_robot_time on telemetry_event(robot_id, observed_at desc);
