create table if not exists calibration_bundle (
  calibration_id varchar(128) primary key,
  digest char(64) not null,
  robot_id varchar(80) not null,
  product_id varchar(128) not null,
  sensor_rig_id varchar(128) not null,
  state varchar(16) not null check (state in ('draft','approved','superseded')),
  bundle_json jsonb not null,
  approved_by varchar(128),
  approved_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists policy_release (
  policy_id varchar(128) primary key,
  digest char(64) not null,
  algorithm varchar(32) not null check (algorithm = 'bc_rnn'),
  target_product_id varchar(128) not null,
  capability_profile_digest varchar(71) not null check (capability_profile_digest ~ '^sha256:[a-f0-9]{64}$'),
  model_sha256 char(64) not null,
  dataset_sha256 char(64) not null,
  success_rate double precision not null check (success_rate between 0 and 1),
  stress_success_rate double precision not null check (stress_success_rate between 0 and 1),
  state varchar(16) not null check (state in ('trained','evaluated','approved','deployed','retired','rolled_back')),
  release_json jsonb not null,
  approved_by varchar(128),
  approved_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists perception_result (
  result_id varchar(128) primary key,
  digest char(64) not null,
  robot_id varchar(80) not null,
  scene_sequence bigint not null check (scene_sequence >= 0),
  calibration_id varchar(128) not null references calibration_bundle(calibration_id),
  policy_id varchar(128) not null references policy_release(policy_id),
  result_json jsonb not null,
  created_at timestamptz not null default now()
);

create table if not exists execution_intent (
  intent_id varchar(128) primary key,
  digest char(64) not null,
  result_id varchar(128) not null references perception_result(result_id),
  grasp_id varchar(128) not null,
  robot_id varchar(80) not null,
  expected_state_version bigint not null check (expected_state_version >= 0),
  expires_at timestamptz not null,
  requested_by varchar(128) not null,
  approved_by varchar(128),
  state varchar(16) not null check (state in ('pending','approved','dispatched','rejected','failed','expired')),
  intent_json jsonb not null,
  command_id varchar(128),
  command_response jsonb,
  error_code varchar(64),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists robot_command_outbox (
  intent_id varchar(128) primary key references execution_intent(intent_id),
  state varchar(16) not null check (state in ('pending','dispatching','dispatched','failed')),
  attempts integer not null default 0 check (attempts between 0 and 20),
  lease_until timestamptz,
  last_error varchar(512),
  updated_at timestamptz not null default now()
);

create index if not exists perception_result_robot_scene
  on perception_result(robot_id, scene_sequence desc);
create index if not exists execution_intent_state_time
  on execution_intent(state, expires_at);
create index if not exists robot_command_outbox_state_lease
  on robot_command_outbox(state, lease_until);
