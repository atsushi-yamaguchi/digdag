package io.digdag.core.database.migrate;

import org.skife.jdbi.v2.Handle;

public class Migration_20151204221156_CreateTables
        implements Migration
{
    @Override
    public void migrate(Handle handle, MigrationContext context)
    {
        if (context.isPostgres()) {
            // check existance of extension first because CREATE EXTENSION is allowed only for superuser
            String ver = handle.createQuery("select installed_version from pg_catalog.pg_available_extensions where name = 'uuid-ossp'")
                .mapTo(String.class)
                .first();
            if (ver == null) {
                handle.update("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
            }
        }

        // projects
        handle.update(
                context.newCreateTableBuilder("projects")
                .addIntId("id")
                .addInt("site_id", "not null")
                .addString("name", "not null")
                .addTimestamp("created_at", "not null")
                //.addTimestamp("deleted_at", "not null")  // this points UNIXTIME 0 (1970-01-01 00:00:00 UTC) if this project is not deleted
                .build());
        handle.update("create unique index projects_on_site_id_and_name on projects (site_id, name)");
        //handle.update("create unique index projects_on_site_id_and_name on projects (site_id, name, deleted_at)");

        // revisions
        handle.update(
                context.newCreateTableBuilder("revisions")
                .addIntId("id")
                .addInt("project_id", "not null references projects (id)")
                .addString("name", "not null")
                // TODO disabled flag
                .addMediumText("default_params", "")  // TODO move this to revision_params as like workflow_configs
                .addString("archive_type", "not null")
                .addString("archive_path", "")
                .addBinary("archive_md5", "")
                .addTimestamp("created_at", "not null")
                .build());
        handle.update("create unique index revisions_on_project_id_and_name on revisions (project_id, name)");
        handle.update("create index revisions_on_project_id_and_id on revisions (project_id, id desc)");

        // revision_archives
        handle.update(
                context.newCreateTableBuilder("revision_archives")
                .addIntIdNoAutoIncrement("id", "references revisions (id)")
                .addLongBinary("archive_data", "not null")
                .build());

        // workflow_configs
        handle.update(
                context.newCreateTableBuilder("workflow_configs")
                .addIntId("id")
                .addInt("project_id", "not null references projects (id)")
                .addLong("config_digest", "not null")
                .addString("timezone", "not null")
                .addMediumText("config", "not null")
                .build());
        handle.update("create index workflow_configs_on_project_id_and_config_digest on workflow_configs (project_id, config_digest)");

        // workflow_definitions
        handle.update(
                context.newCreateTableBuilder("workflow_definitions")
                .addLongId("id")
                .addInt("config_id", "not null references workflow_configs (id)")
                .addInt("revision_id", "not null references revisions (id)")
                .addString("name", "not null")
                .build());
        handle.update("create unique index workflow_definitions_on_revision_id_and_name on workflow_definitions (revision_id, name)");

        // schedules
        handle.update(
                context.newCreateTableBuilder("schedules")
                .addIntId("id")
                .addInt("project_id", "not null references projects (id)")
                .addLong("workflow_definition_id", "not null references workflow_definitions (id)")
                .addLong("next_run_time", "not null")
                .addLong("next_schedule_time", "not null")
                .addLong("last_session_time", "")
                .addTimestamp("created_at", "not null")
                .addTimestamp("updated_at", "not null")
                .build());
        handle.update("create index schedules_on_project_id on schedules (project_id)");
        handle.update("create unique index schedules_on_workflow_definition_id on schedules (workflow_definition_id)");
        handle.update("create index schedules_on_next_run_time on schedules (next_run_time)");

        // sessions
        handle.update(
                context.newCreateTableBuilder("sessions")
                .addLongId("id")
                .addInt("project_id", "not null references projects (id)")
                .addString("workflow_name", "not null")
                .addLong("session_time", "not null")
                .addUuid("session_uuid", context.isPostgres() ? "not null default(uuid_generate_v4())" : "not null default(RANDOM_UUID())")
                .addLong("last_attempt_id", "")
                .build());
        handle.update("create unique index sessions_on_project_id_and_workflow_name_and_session_time on sessions (project_id, workflow_name, session_time)");
        handle.update("create index sessions_on_project_id on sessions (project_id, id)");

        // session_attempts
        handle.update(
                context.newCreateTableBuilder("session_attempts")
                .addLongId("id")
                .addLong("session_id", "not null references sessions (id)")
                .addInt("site_id", "not null")  // denormalized for performance
                .addInt("project_id", "not null references projects (id)")  // denormalized for performance
                .addString("attempt_name", "not null")
                .addLong("workflow_definition_id", "references workflow_definitions (id)")
                .addShort("state_flags", "not null")  // 0=running or blocked, 1=cancel_requested, 2=done, 4=success
                .addString("timezone", "not null")
                .addMediumText("params", "")
                .addTimestamp("created_at", "not null")
                .build());
        handle.update("create unique index session_attempts_on_session_id_and_attempt_name on session_attempts (session_id, attempt_name)");
        handle.update("create index session_attempts_on_site_id on session_attempts (site_id, id desc)");
        handle.update("create index session_attempts_on_workflow_definition_id on session_attempts (workflow_definition_id, id desc)");
        handle.update("create index session_attempts_on_project_id on session_attempts (project_id, id desc)");

        // task_archives
        handle.update(
                context.newCreateTableBuilder("task_archives")
                .addLongIdNoAutoIncrement("id", "references session_attempts (id)")
                .addLongText("tasks", "not null")  // collection of tasks, delete tasks transactionally when archived
                .addTimestamp("created_at", "not null")
                .build());

        // session_monitors
        handle.update(
                context.newCreateTableBuilder("session_monitors")
                .addLongId("id")
                .addLong("attempt_id", "not null")
                .addLong("next_run_time", "not null")
                .addString("type", "not null")
                .addMediumText("config", "")
                .addTimestamp("created_at", "not null")
                .addTimestamp("updated_at", "not null")
                .build());
        handle.update("create index session_monitors_on_attempt_id on session_monitors (attempt_id)");
        handle.update("create index session_monitors_on_next_run_time on session_monitors (next_run_time)");

        // tasks
        handle.update(
                context.newCreateTableBuilder("tasks")
                .addLongId("id")
                .addLong("attempt_id", "not null references session_attempts (id)")
                .addLong("parent_id", "references tasks (id)")
                .addShort("task_type", "not null")   // 0=action, 1=grouping
                //.addShort("error_mode", "not null")  // 1=ignore_parent_flags
                .addShort("state", "not null")
                .addShort("state_flags", "not null")
                .addTimestamp("updated_at", "not null")  // last state update is done at this time
                .addTimestamp("retry_at", "")
                .addMediumText("state_params", "")
                .build());
        handle.update("create index tasks_on_attempt_id on tasks (attempt_id, id)");
        handle.update("create index tasks_on_parent_id_and_state on tasks (parent_id, state)");
        if (context.isPostgres()) {
            // for findTasksByState(BLOCKED) at propagateAllBlockedToReady
            // for findTasksByState(PLANNED) at propagateAllPlannedToDone
            // for findTasksByState(READY) through findAllReadyTaskIds() at enqueueReadyTasks
            handle.update("create index tasks_on_state_and_id on tasks (state, id) where state = 0 or state = 1 or state = 5");
        }
        else {
            // for findTasksByState
            handle.update("create index tasks_on_state_and_id on tasks (state, id)");
        }

        handle.update(
                context.newCreateTableBuilder("task_details")
                .addLongIdNoAutoIncrement("id", "references tasks (id)")
                .addMediumText("full_name", "not null")
                .addMediumText("local_config", "")
                .addMediumText("export_config", "")
                .build());

        handle.update(
                context.newCreateTableBuilder("task_state_details")
                .addLongIdNoAutoIncrement("id", "references tasks (id)")
                .addMediumText("subtask_config", "")
                .addMediumText("export_params", "")
                .addMediumText("store_params", "")
                .addMediumText("report", "")
                .addMediumText("error", "")
                .build());

        // task_dependencies
        handle.update(
                context.newCreateTableBuilder("task_dependencies")
                .addLongId("id")
                .addLong("upstream_id", "not null")
                .addLong("downstream_id", "not null")
                .build());
        handle.update("create index task_dependencies_on_downstream_id on task_dependencies (downstream_id)");

        // queue_settings
        handle.update(
                context.newCreateTableBuilder("queue_settings")
                .addIntId("id")
                .addInt("site_id", "not null")
                .addString("name", "not null")
                .addMediumText("config", "")
                .addTimestamp("created_at", "not null")
                .addTimestamp("updated_at", "not null")
                .build());
        handle.update("create unique index queue_settings_on_site_id_and_name on queue_settings (site_id, name)");
        handle.update("create index queue_settings_on_site_id on queue_settings (site_id, id)");

        // queues
        handle.update(
                context.newCreateTableBuilder("queues")
                .addIntIdNoAutoIncrement("id", "references queue_settings (id)")
                .addInt("max_concurrency", "not null")
                .build());

        // resource_types
        handle.update(
                context.newCreateTableBuilder("resource_types")
                .addIntId("id")
                .addInt("queue_id", "not null references queues (id)")
                .addInt("max_concurrency", "not null")
                .addString("name", "not null")
                .build());
        handle.update("create unique index resource_types_on_queue_id_and_name on resource_types (queue_id, name)");

        // queued_tasks
        handle.update(
                context.newCreateTableBuilder("queued_tasks")
                .addLongId("id")
                .addInt("site_id", "not null")  // denormalized for performance
                .addInt("queue_id", "not null")
                .addInt("priority", "not null")
                .addInt("resource_type_id", "")
                .addLong("task_id", "not null")
                .addTimestamp("created_at", "not null")
                .addLongBinary("data", "not null")
                .build());
        handle.update("create unique index queued_tasks_on_queue_id_task_id on queued_tasks (queue_id, task_id)");

        // queued_shared_task_locks
        handle.update(
                context.newCreateTableBuilder("queued_shared_task_locks")
                .addLongId("id")  // references queued_tasks.id
                .addInt("queue_id", "not null")
                .addInt("priority", "not null")
                .addInt("resource_type_id", "")
                .addInt("retry_count", "not null")
                .addLong("hold_expire_time", "")
                .addString("hold_agent_id", "")
                .build());

        // queued_task_locks
        handle.update(
                context.newCreateTableBuilder("queued_task_locks")
                .addLongId("id")  // references queued_tasks.id
                .addInt("queue_id", "not null")
                .addInt("priority", "not null")
                .addInt("resource_type_id", "")
                .addInt("retry_count", "not null")
                .addLong("hold_expire_time", "")
                .addString("hold_agent_id", "")
                .build());

        if (context.isPostgres()) {
            handle.update("create index queued_shared_task_locks_grouping on queued_shared_task_locks (queue_id, resource_type_id) where hold_expire_time is not null");
            handle.update("create index queued_shared_task_locks_ordering on queued_shared_task_locks (queue_id, priority desc, id) where hold_expire_time is null");
            handle.update("create index queued_shared_task_locks_expiration on queued_shared_task_locks (hold_expire_time) where hold_expire_time is not null");
            handle.update("create index queued_task_locks_grouping on queued_task_locks (queue_id, resource_type_id) where hold_expire_time is not null");
            handle.update("create index queued_task_locks_ordering on queued_task_locks (queue_id, priority desc, id) where hold_expire_time is null");
            handle.update("create index queued_task_locks_expiration on queued_task_locks (hold_expire_time) where hold_expire_time is not null");
        }
        else {
            handle.update("create index queued_shared_task_locks_grouping on queued_shared_task_locks (hold_expire_time, queue_id, resource_type_id)");
            handle.update("create index queued_shared_task_locks_ordering on queued_shared_task_locks (queue_id, hold_expire_time, priority desc, id)");
            handle.update("create index queued_task_locks_grouping on queued_task_locks (hold_expire_time, queue_id, resource_type_id)");
            handle.update("create index queued_task_locks_ordering on queued_task_locks (queue_id, hold_expire_time, priority desc, id)");
        }
    }
}
