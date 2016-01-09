package io.digdag.client.api;

import java.util.Date;
import java.util.List;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.immutables.value.Value;
import io.digdag.spi.config.Config;
import io.digdag.core.session.StoredTask;

@Value.Immutable
@JsonSerialize(as = ImmutableRestTask.class)
@JsonDeserialize(as = ImmutableRestTask.class)
public abstract class RestTask
{
    public abstract long getId();

    public abstract String getFullName();

    public abstract Long getParentId();

    public abstract Config getConfig();

    public abstract List<Long> getUpstreams();

    public abstract boolean isGroup();

    public abstract String getState();

    public abstract Config getCarryParams();

    public abstract Config getStateParams();

    public abstract Date getUpdatedAt();

    public abstract Date getRetryAt();

    // TODO in out Report

    public static ImmutableRestTask.Builder builder()
    {
        return ImmutableRestTask.builder();
    }

    public static RestTask of(StoredTask task)
    {
        return builder()
            .id(task.getId())
            .fullName(task.getFullName())
            .parentId(task.getParentId().orNull())
            .config(task.getConfig().getNonValidated())
            .upstreams(task.getUpstreams())
            .isGroup(task.getTaskType().isGroupingOnly())
            .state(task.getState().toString().toLowerCase())
            .carryParams(task.getReport().transform(report -> report.getCarryParams()).or(task.getConfig().getLocal().getFactory().create()))
            .stateParams(task.getStateParams())
            .build();
    }
}