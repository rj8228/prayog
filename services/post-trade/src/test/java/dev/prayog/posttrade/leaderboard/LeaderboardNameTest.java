package dev.prayog.posttrade.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

class LeaderboardNameTest {

    @Test
    void aFailedNameWriteIsRetriedOnTheNextRequest() {
        AtomicInteger writes = new AtomicInteger();
        MockConnection connection = new MockConnection(ctx -> {
            if (writes.incrementAndGet() == 1) {
                throw new SQLException("database restarting");
            }
            return new MockResult[] {new MockResult(1)};
        });
        Leaderboard board = new Leaderboard(null, null, DSL.using(connection, SQLDialect.POSTGRES));

        assertThatThrownBy(() -> board.name(42, "trader1", "main")).hasRootCauseInstanceOf(SQLException.class);
        board.name(42, "trader1", "main");
        board.name(42, "trader1", "main"); // once written, never again in this process

        assertThat(writes).hasValue(2);
    }
}
