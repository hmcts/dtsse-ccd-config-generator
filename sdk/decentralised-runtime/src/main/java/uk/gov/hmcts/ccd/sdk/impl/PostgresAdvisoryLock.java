package uk.gov.hmcts.ccd.sdk.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public final class PostgresAdvisoryLock {

  private final DataSource dataSource;

  public boolean runIfAcquired(String namespace, String name, Runnable operation) {
    return runIfAcquired(namespace, name, () -> {
      operation.run();
      return true;
    }).isPresent();
  }

  public <T> Optional<T> runIfAcquired(String namespace, String name, Supplier<T> operation) {
    try (var lock = tryAcquire(namespace, name)) {
      if (lock == null) {
        return Optional.empty();
      }
      return Optional.of(operation.get());
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Could not use PostgreSQL advisory lock namespace=" + namespace + " name=" + name,
          exception
      );
    }
  }

  private Lock tryAcquire(String namespace, String name) throws SQLException {
    Connection connection = null;
    try {
      connection = dataSource.getConnection();
      try (PreparedStatement statement = connection.prepareStatement(
          "select pg_try_advisory_lock(hashtext(?), hashtext(?))"
      )) {
        statement.setString(1, namespace);
        statement.setString(2, name);
        try (ResultSet result = statement.executeQuery()) {
          if (result.next() && result.getBoolean(1)) {
            return new Lock(connection, namespace, name);
          }
        }
      }
      connection.close();
      return null;
    } catch (SQLException exception) {
      closeConnection(connection, namespace, name);
      throw exception;
    }
  }

  private void closeConnection(Connection connection, String namespace, String name) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException exception) {
      log.warn("Failed to close PostgreSQL advisory lock connection namespace={} name={}",
          namespace, name, exception);
    }
  }

  private void abortConnection(Connection connection, String namespace, String name) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException exception) {
      log.warn("Failed to abort PostgreSQL advisory lock connection namespace={} name={}",
          namespace, name, exception);
      closeConnection(connection, namespace, name);
    }
  }

  @RequiredArgsConstructor
  private final class Lock implements AutoCloseable {
    private final Connection connection;
    private final String namespace;
    private final String name;

    @Override
    public void close() {
      boolean unlocked = false;
      try (PreparedStatement statement = connection.prepareStatement(
          "select pg_advisory_unlock(hashtext(?), hashtext(?))"
      )) {
        statement.setString(1, namespace);
        statement.setString(2, name);
        try (ResultSet result = statement.executeQuery()) {
          unlocked = result.next() && result.getBoolean(1);
        }
      } catch (SQLException exception) {
        log.warn("Failed to release PostgreSQL advisory lock namespace={} name={}", namespace, name, exception);
      } finally {
        if (unlocked) {
          closeConnection(connection, namespace, name);
        } else {
          abortConnection(connection, namespace, name);
        }
      }
    }
  }
}
