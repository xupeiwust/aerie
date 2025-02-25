package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import org.intellij.lang.annotations.Language;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/*package-local*/ final class DeleteSimulationExtentAction implements AutoCloseable {
  private final @Language("SQL") String sql = """
        delete from simulation_extent
        using simulation_dataset
        where simulation_dataset.id = simulation_extent.simulation_dataset_id
          and simulation_dataset.dataset_id = ?;
        """;

  private final PreparedStatement statement;

  public DeleteSimulationExtentAction(final Connection connection) throws SQLException {
    this.statement = connection.prepareStatement(sql);
  }

  public void apply(final long datasetId)
  throws SQLException, NoSuchSimulationDatasetException
  {
    this.statement.setLong(1, datasetId);
    this.statement.executeUpdate();
  }

  @Override
  public void close() throws SQLException {
    this.statement.close();
  }
}
