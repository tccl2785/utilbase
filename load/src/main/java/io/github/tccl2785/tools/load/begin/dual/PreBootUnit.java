package io.github.tccl2785.tools.load.begin.dual;

import io.github.tccl2785.tools.load.begin.BootUnit;
import io.github.tccl2785.tools.load.dual.PreLaunchLoadItems;

public abstract class PreBootUnit extends BootUnit {

    PreLaunchLoadItems loadItems0;

    protected PreLaunchLoadItems loadItems() {
        return loadItems0;
    }
}
