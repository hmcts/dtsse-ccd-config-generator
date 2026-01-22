---
config:
  flowchart:
    nodeSpacing: 60
    rankSpacing: 90
  look: classic
  theme: default
---
graph LR

subgraph ECM_ETHOS[ECM & ETHOS]
  ecm_ccd_case_migration["ecm-ccd-case-migration"]
  ecm_consumer["ecm-consumer"]
  ethos_repl_docmosis_service["ethos-repl-docmosis-service"]
  consumerdb[("ecm-consumer-postgres")]
  ethosdb[("ethos-postgres")]
  ethossb{"ethos-sb"}
end
subgraph Common[Common]
  ecm_common["ecm-common"]
  ecm_data_model["ecm-data-model"]
  et_common["et-common"]
  et_data_model["et-data-model"]
end
subgraph EXTERNAL_HMC[External - HMC]
  hmc_sb{"hmc-servicebus"}
end
subgraph EXTERNAL_AAC[External - AAC]
  aac["AAC"]
end
subgraph EXTERNAL_DM_STORE[External - dm-store]
  dm_store["dm-store"]
end
subgraph EXTERNAL_CCD[External - CCD]
  ccd["CCD"]
end
subgraph ET_OTHER[ET]
  et_ccd_callbacks["et-ccd-callbacks"]
  et_ccd_definitions_admin["et-ccd-definitions-admin"]
  et_ccd_definitions_englandwales["et-ccd-definitions-englandwales"]
  et_ccd_definitions_scotland["et-ccd-definitions-scotland"]
  et_cron["et-cron"]
  et_hearings_api["et-hearings-api"]
  et_message_handler["et-message-handler"]
  et_sya_api["et-sya-api"]
  et_sya_frontend["et-sya-frontend"]
  et_syr_frontend["et-syr-frontend"]
  cosdb[("et-cos-postgres")]
  msghanddb[("et-msg-handler-postgres")]
  etsb{"et-sb"}
end
  subgraph ET_PET[ET PET]
    et_pet_admin["et-pet-admin"]
    et_pet_api["et-pet-api"]
    et_pet_ccd_export["et-pet-ccd-export"]
    et_pet_et1["et-pet-et1"]
    et_pet_et3["et-pet-et3"]
    et_pet_shared_infrastructure["et-pet-shared-infrastructure"]
    petet[("et-pet-et")]
  end
ecm_ccd_case_migration -->|http| ecm_consumer
ecm_ccd_case_migration -->|library| ecm_data_model
ecm_common -->|http| et_common
ecm_common -->|library| ecm_data_model
ecm_common -->|library| et_data_model
ecm_consumer -->|http| ecm_common
ecm_consumer -->|http| et_common
ecm_consumer -->|library| ecm_common
ecm_consumer -->|library| ecm_data_model
ecm_consumer -->|library| et_data_model
ecm_data_model -->|http| ecm_common
ecm_data_model -->|http| et_common
et_ccd_callbacks -->|http| ecm_common
et_ccd_callbacks -->|http| et_common
et_ccd_callbacks -->|http| et_sya_api
et_ccd_callbacks -->|library| ecm_common
et_ccd_callbacks -->|library| ecm_data_model
et_ccd_callbacks -->|library| et_common
et_ccd_callbacks -->|library| et_data_model
et_ccd_callbacks -->|library| et_sya_frontend
et_ccd_definitions_admin -->|http| et_sya_api
et_ccd_definitions_admin -->|library| et_ccd_definitions_englandwales
et_ccd_definitions_admin -->|library| et_ccd_definitions_scotland
et_ccd_definitions_englandwales -->|http| et_ccd_definitions_admin
et_ccd_definitions_scotland -->|http| et_ccd_definitions_admin
et_common -->|http| ecm_common
et_common -->|library| ecm_common
et_common -->|library| ecm_data_model
et_common -->|library| et_data_model
et_data_model -->|http| ecm_common
et_data_model -->|http| et_common
et_hearings_api -->|library| et_common
et_hearings_api -->|library| et_data_model
et_message_handler -->|http| ecm_common
et_message_handler -->|http| et_common
et_message_handler -->|library| ecm_common
et_message_handler -->|library| ecm_data_model
et_message_handler -->|library| et_common
et_message_handler -->|library| et_data_model
et_pet_admin -->|http| et_pet_api
et_pet_ccd_export -->|http| et_pet_api
et_pet_et1 -->|http| et_pet_api
et_pet_et3 -->|http| et_pet_api
et_pet_shared_infrastructure -->|http| et_pet_api
et_sya_api -->|http| ecm_common
et_sya_api -->|http| et_common
et_sya_api -->|library| et_common
et_sya_api -->|library| et_data_model
et_sya_frontend -->|http| et_pet_et1
et_sya_frontend -->|http| et_sya_api
et_syr_frontend -->|http| et_pet_et1
et_syr_frontend -->|http| et_sya_api
ethos_repl_docmosis_service -->|http| ecm_common
ethos_repl_docmosis_service -->|http| et_common
ethos_repl_docmosis_service -->|library| ecm_common
ethos_repl_docmosis_service -->|library| ecm_data_model
ethos_repl_docmosis_service -->|library| et_data_model
ecm_consumer -->|postgres| consumerdb
ethos_repl_docmosis_service -->|postgres| ethosdb
ecm_consumer -->|servicebus| ethossb
ethos_repl_docmosis_service -->|servicebus| ethossb
et_ccd_callbacks -->|postgres| cosdb
et_cron -->|postgres| cosdb
et_message_handler -->|postgres| msghanddb
et_message_handler -->|servicebus| etsb
et_ccd_callbacks -->|servicebus| etsb
et_cron -->|servicebus| etsb
et_pet_api -->|postgres| petet
et_pet_admin -->|postgres| petet
et_pet_et1 -->|postgres| petet
et_pet_et3 -->|postgres| petet
et_hearings_api -->|servicebus| hmc_sb
et_ccd_callbacks -->|http| aac
et_ccd_definitions_admin -->|http| aac
et_ccd_definitions_englandwales -->|http| aac
et_ccd_definitions_scotland -->|http| aac
et_cron -->|http| aac
et_sya_api -->|http| aac
et_ccd_callbacks -->|http| dm_store
et_ccd_definitions_admin -->|http| dm_store
et_common -->|http| dm_store
et_cron -->|http| dm_store
et_hearings_api -->|http| dm_store
et_pet_ccd_export -->|http| dm_store
et_sya_api -->|http| dm_store
et_sya_frontend -->|http| dm_store
et_syr_frontend -->|http| dm_store
ethos_repl_docmosis_service -->|http| dm_store
et_ccd_callbacks -->|http| ccd
et_ccd_definitions_admin -->|http| ccd
et_cron -->|http| ccd
ethos_repl_docmosis_service -->|http| ccd
classDef db fill:#ff7f0e,color:#fff,stroke:#ff7f0e;
classDef queue fill:#8c564b,color:#fff,stroke:#8c564b;
classDef service fill:#1f77b4,color:#fff,stroke:#1f77b4;
classDef lib fill:#2ca02c,color:#fff,stroke:#2ca02c;
classDef test fill:#e377c2,color:#fff,stroke:#e377c2;
class consumerdb db;
class ethosdb db;
class petet db;
class cosdb db;
class msghanddb db;
class ethossb queue;
class etsb queue;
class ecm_ccd_case_migration service;
class ecm_consumer service;
class et_ccd_callbacks service;
class et_hearings_api service;
class et_message_handler service;
class et_pet_admin service;
class et_pet_api service;
class et_pet_ccd_export service;
class et_pet_et1 service;
class et_pet_et3 service;
class et_pet_shared_infrastructure service;
class et_sya_api service;
class et_syr_frontend service;
class ethos_repl_docmosis_service service;
class ecm_common lib;
class ecm_data_model lib;
class et_ccd_definitions_englandwales lib;
class et_ccd_definitions_scotland lib;
class et_common lib;
class et_data_model lib;
class hmc_sb queue;
class et_ccd_definitions_admin test;
class et_cron lib;
class et_sya_frontend service;