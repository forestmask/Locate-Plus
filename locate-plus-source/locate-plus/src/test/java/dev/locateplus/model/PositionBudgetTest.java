/*
 * Locate Plus
 * Copyright (C) 2026 forest_mask
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.locateplus.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget is the only thing standing between a huge forceload export and an out-of-memory kill,
 * so its edges are worth pinning down.
 */
class PositionBudgetTest {

    @Test
    @DisplayName("claims succeed up to the limit and fail afterwards")
    void enforcesLimit() {
        PositionBudget budget = new PositionBudget(3);
        assertTrue(budget.claim());
        assertTrue(budget.claim());
        assertTrue(budget.claim());
        assertFalse(budget.claim());
        assertFalse(budget.claim());
        assertEquals(3, budget.used());
    }

    @Test
    @DisplayName("exhaustion is only flagged once the limit is actually hit")
    void tracksExhaustion() {
        PositionBudget budget = new PositionBudget(2);
        assertFalse(budget.exhausted());
        budget.claim();
        budget.claim();
        assertFalse(budget.exhausted(), "not exhausted until a claim is refused");
        budget.claim();
        assertTrue(budget.exhausted());
    }

    @Test
    @DisplayName("a shared budget caps the total across every block type in one scan")
    void sharedAcrossTallies() {
        PositionBudget budget = new PositionBudget(5);
        int accepted = 0;
        for (int type = 0; type < 3; type++) {
            for (int i = 0; i < 4; i++) {
                if (budget.claim()) {
                    accepted++;
                }
            }
        }
        assertEquals(5, accepted, "12 attempts across 3 types must still stop at the shared limit");
        assertTrue(budget.exhausted());
    }

    @Test
    @DisplayName("a zero budget refuses immediately")
    void zeroBudget() {
        PositionBudget budget = new PositionBudget(0);
        assertFalse(budget.claim());
        assertTrue(budget.exhausted());
    }
}
