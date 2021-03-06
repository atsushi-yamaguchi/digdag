package io.digdag.plugin.example;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.common.base.Throwables;
import io.digdag.client.config.Config;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorFactory;
import io.digdag.spi.TaskRequest;
import io.digdag.spi.TaskResult;
import io.digdag.spi.TemplateEngine;
import io.digdag.util.BaseOperator;
import static java.nio.charset.StandardCharsets.UTF_8;

public class ExampleOperatorFactory
        implements OperatorFactory
{
    private final TemplateEngine templateEngine;

    public ExampleOperatorFactory(TemplateEngine templateEngine)
    {
        this.templateEngine = templateEngine;
    }

    public String getType()
    {
        return "example";
    }

    @Override
    public Operator newTaskExecutor(Path workspacePath, TaskRequest request)
    {
        return new ExampleOperator(workspacePath, request);
    }

    private class ExampleOperator
            extends BaseOperator
    {
        public ExampleOperator(Path workspacePath, TaskRequest request)
        {
            super(workspacePath, request);
        }

        @Override
        public TaskResult runTask()
        {
            Config params = request.getConfig().mergeDefault(
                    request.getConfig().getNestedOrGetEmpty("example"));

            String message = workspace.templateCommand(templateEngine, params, "message", UTF_8);
            String path = params.get("path", String.class);

            try {
                Files.write(workspace.getPath(path), message.getBytes(UTF_8));
            }
            catch (IOException ex) {
                throw Throwables.propagate(ex);
            }

            return TaskResult.empty(request);
        }
    }
}
