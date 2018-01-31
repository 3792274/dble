/*
 * Copyright (C) 2016-2018 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.jsdt;

import com.sun.tracing.ProviderFactory;

public final class CostTimeProviderFactory {
    private CostTimeProviderFactory() {
    }

    public static CostTimeProvider getProvider() {
        return provider;
    }

    private static CostTimeProvider provider;

    static {
        ProviderFactory factory = ProviderFactory.getDefaultFactory();
        provider = factory.createProvider(CostTimeProvider.class);
    }
}
