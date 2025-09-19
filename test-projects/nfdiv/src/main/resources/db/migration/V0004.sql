create table civil.payment
(
  case_reference            bigint references ccd.case_data (reference),
  id                        varchar(200) not null ,
  created                   timestamp not null ,
  updated                   timestamp,
  fee_code                  varchar(200) not null ,
  amount                    decimal      not null,
  status                    varchar(200) not null ,
  channel                   varchar(200) not null ,
  reference                 varchar(200) not null ,
  transaction_id            varchar(200),
  service_request_reference varchar(200),
  primary key (id)
);
