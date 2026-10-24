package org.fleetloom.vda5050.v2;

import java.util.List;

public record Info(String infoType, List<InfoReference> infoReferences, String infoDescription, InfoLevel infoLevel) {

    public Info {
        infoReferences = infoReferences == null ? null : List.copyOf(infoReferences);
    }
}
