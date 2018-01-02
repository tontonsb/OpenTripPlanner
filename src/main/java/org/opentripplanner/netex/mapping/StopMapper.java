package org.opentripplanner.netex.mapping;

import com.google.common.collect.Iterables;
import org.opentripplanner.model.AgencyAndId;
import org.opentripplanner.model.Stop;
import org.opentripplanner.model.impl.OtpTransitBuilder;
import org.opentripplanner.netex.loader.NetexDao;
import org.rutebanken.netex.model.Quay;
import org.rutebanken.netex.model.StopPlace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class StopMapper {
    private static final Logger LOG = LoggerFactory.getLogger(StopMapper.class);

    private StopPlaceTypeMapper transportModeMapper  = new StopPlaceTypeMapper();

    public Collection<Stop> mapParentAndChildStops(Collection<StopPlace> stopPlaceAllVersions, OtpTransitBuilder transitBuilder, NetexDao netexDao){
        ArrayList<Stop> stops = new ArrayList<>();

        Stop multiModalStop = null;
        Stop stop = new Stop();
        stop.setLocationType(1);

        // Sort by versions, latest first
        stopPlaceAllVersions = stopPlaceAllVersions.stream()
                .sorted(Comparator.comparingInt(o -> Integer.parseInt(o.getVersion())))
                .collect(Collectors.toList());

        StopPlace stopPlaceLatest = Iterables.getLast(stopPlaceAllVersions);

        if (stopPlaceLatest.getParentSiteRef() != null) {
            AgencyAndId id = AgencyAndIdFactory.createAgencyAndId(stopPlaceLatest.getParentSiteRef().getRef());
            if (transitBuilder.getMultiModalStops().containsKey(id)) {
                multiModalStop = transitBuilder.getMultiModalStops().get(id);
                transitBuilder.getStationsByMultiModalStop().put(multiModalStop, stop);
            }
        }

        if (stopPlaceLatest.getName() != null) {
            stop.setName(stopPlaceLatest.getName().getValue());
        } else if (multiModalStop != null) {
            String parentName = multiModalStop.getName();
            if (parentName != null) {
                stop.setName(parentName);
            } else {
                LOG.warn("No name found for stop " + stopPlaceLatest.getId() + " or in parent stop");
                stop.setName("N/A");
            }
        } else {
            stop.setName("N/A");
        }

        if(stopPlaceLatest.getCentroid() != null){
            stop.setLat(stopPlaceLatest.getCentroid().getLocation().getLatitude().doubleValue());
            stop.setLon(stopPlaceLatest.getCentroid().getLocation().getLongitude().doubleValue());
        }else{
            LOG.warn(stopPlaceLatest.getId() + " does not contain any coordinates.");
        }

        stop.setId(AgencyAndIdFactory.createAgencyAndId(stopPlaceLatest.getId()));

        stop.setVehicleType(transportModeMapper.getTransportMode(stopPlaceLatest));

        if (stopPlaceLatest.getAccessibilityAssessment() != null
                && stopPlaceLatest.getAccessibilityAssessment().getLimitations() != null
                && stopPlaceLatest.getAccessibilityAssessment().getLimitations().getAccessibilityLimitation() != null &&
                stopPlaceLatest.getAccessibilityAssessment().getLimitations().getAccessibilityLimitation().getWheelchairAccess() != null){
            switch (stopPlaceLatest.getAccessibilityAssessment().getLimitations().getAccessibilityLimitation().getWheelchairAccess().value()) {
                case "true":
                    stop.setWheelchairBoarding(1);
                    break;
                case "false":
                    stop.setWheelchairBoarding(2);
                    break;
                case "unknown":
                    stop.setWheelchairBoarding(1);
                    break;
                default:
                    stop.setWheelchairBoarding(0);
                    break;
            }
        }
        else {
            stop.setWheelchairBoarding(0);
        }

        if (stopPlaceLatest.getDescription() != null) {
            stop.setDesc(stopPlaceLatest.getDescription().getValue());
        }

        stops.add(stop);

        // Get quays from all versions of stop place
        Set<String> quaysSeen = new HashSet<>();

        for (StopPlace stopPlace : stopPlaceAllVersions) {
            if (stopPlace.getQuays() != null) {
                List<Object> quayRefOrQuay = stopPlace.getQuays().getQuayRefOrQuay();
                for (Object quayObject : quayRefOrQuay) {
                    if (quayObject instanceof Quay) {
                        Quay quay = (Quay) quayObject;
                        Stop stopQuay = new Stop();
                        stopQuay.setLocationType(0);
                        if (quay.getCentroid() == null || quay.getCentroid().getLocation() == null
                                || quay.getCentroid().getLocation().getLatitude() == null
                                || quay.getCentroid().getLocation().getLatitude() == null) {
                            LOG.warn("Quay " + quay.getId() + " does not contain any coordinates.");
                            continue;
                        }
                        stopQuay.setName(stop.getName());
                        stopQuay.setLat(quay.getCentroid().getLocation().getLatitude().doubleValue());
                        stopQuay.setLon(quay.getCentroid().getLocation().getLongitude().doubleValue());
                        stopQuay.setId(AgencyAndIdFactory.createAgencyAndId(quay.getId()));
                        stopQuay.setPlatformCode(quay.getPublicCode());
                        stopQuay.setVehicleType(stop.getVehicleType());
                        stopQuay.setParentStation(stop.getId().getId());
                        if (quay.getDescription() != null) {
                            stopQuay.setDesc(quay.getDescription().getValue());
                        }
                        if (multiModalStop != null) {
                            stopQuay.setMultiModalStation(multiModalStop.getId().getId());
                        }

                        if (quay.getAccessibilityAssessment() != null
                                && quay.getAccessibilityAssessment().getLimitations() != null
                                && quay.getAccessibilityAssessment().getLimitations().getAccessibilityLimitation() != null &&
                                quay.getAccessibilityAssessment().getLimitations().getAccessibilityLimitation().getWheelchairAccess() != null){
                            switch (quay.getAccessibilityAssessment().getLimitations().getAccessibilityLimitation().getWheelchairAccess().value()) {
                                case "true":
                                    stopQuay.setWheelchairBoarding(1);
                                    break;
                                case "false":
                                    stopQuay.setWheelchairBoarding(2);
                                    break;
                                case "unknown":
                                    stopQuay.setWheelchairBoarding(1);
                                    break;
                                default:
                                    stopQuay.setWheelchairBoarding(0);
                                    break;
                            }
                        } else {
                            stopQuay.setWheelchairBoarding(stop.getWheelchairBoarding());
                        }

                        // Continue if this is not newest version of quay
                        if (netexDao.quayById.lookup(stopQuay.getId().getId()).stream()
                                .anyMatch(q -> Integer.parseInt(q.getVersion()) > Integer.parseInt(quay.getVersion()))) {
                            continue;
                        }

                        if (!quaysSeen.contains(quay.getId())) {
                            stops.add(stopQuay);
                            quaysSeen.add(quay.getId());
                        }
                    }
                }
            }
        }
        return stops;
    }

    // Mapped same way as parent stops for now
    Stop mapMultiModalStop(StopPlace stopPlace) {
        Stop stop = new Stop();
        stop.setId(AgencyAndIdFactory.createAgencyAndId(stopPlace.getId()));
        stop.setLocationType(1); // Set same as parent stop for now
        if (stopPlace.getName() != null) {
            stop.setName(stopPlace.getName().getValue());
        } else {

            LOG.warn("No name found for stop " + stopPlace.getId());
            stop.setName("Not found");
        }
        if(stopPlace.getCentroid() != null){
            stop.setLat(stopPlace.getCentroid().getLocation().getLatitude().doubleValue());
            stop.setLon(stopPlace.getCentroid().getLocation().getLongitude().doubleValue());
        }else{
            LOG.warn(stopPlace.getId() + " does not contain any coordinates.");
        }

        return stop;
    }
}