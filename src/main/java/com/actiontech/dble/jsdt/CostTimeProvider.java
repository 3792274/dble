/*
 * Copyright (C) 2016-2018 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.jsdt;

import com.sun.tracing.Provider;

public interface CostTimeProvider extends Provider {
    void beginRequest();

    void beginResponse();
}
