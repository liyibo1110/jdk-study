package com.github.liyibo1110.jdk.java.time.zone;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.zone.Ser;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 定义单一时区时区偏移量如何变化的规则。
 * 这些规则描述了时区所有历史和未来的转换。ZoneOffsetTransition用于已知的转换（通常是历史数据），而ZoneOffsetTransitionRule用于基于算法结果的未来转换。
 *
 * 这些规则是通过ZoneRulesProvider并使用ZoneId加载的。同一套规则可在多个时区ID之间进行内部共享。
 * 序列化ZoneRules实例将存储整套规则。由于时区ID不属于该对象的状态，因此不会被存储。
 *
 * 规则实现可能存储也可能不存储关于历史和未来转换的完整信息，且所存储信息的准确性仅取决于规则提供者向实现提供的数据。
 * 应用程序应将提供的数据视为该规则实现所能获取的最佳信息
 * @author liyibo
 * @date 2026-03-31 11:18
 */
public final class ZoneRules implements Serializable {
    private static final long serialVersionUID = 3044319355680032515L;

    private static final int LAST_CACHED_YEAR = 2100;

    private final long[] standardTransitions;

    private final ZoneOffset[] standardOffsets;

    private final long[] savingsInstantTransitions;

    private final LocalDateTime[] savingsLocalTransitions;

    private final ZoneOffset[] wallOffsets;

    private final ZoneOffsetTransitionRule[] lastRules;

    private final transient ConcurrentMap<Integer, ZoneOffsetTransition[]> lastRulesCache = new ConcurrentHashMap<>();

    private static final long[] EMPTY_LONG_ARRAY = new long[0];

    private static final ZoneOffsetTransitionRule[] EMPTY_LASTRULES = new ZoneOffsetTransitionRule[0];

    private static final LocalDateTime[] EMPTY_LDT_ARRAY = new LocalDateTime[0];

    private static final int DAYS_PER_CYCLE = 146097;

    private static final long DAYS_0000_TO_1970 = (DAYS_PER_CYCLE * 5L) - (30L * 365L + 7L);

    public static ZoneRules of(ZoneOffset baseStandardOffset,
                               ZoneOffset baseWallOffset,
                               List<ZoneOffsetTransition> standardOffsetTransitionList,
                               List<ZoneOffsetTransition> transitionList,
                               List<ZoneOffsetTransitionRule> lastRules) {
        Objects.requireNonNull(baseStandardOffset, "baseStandardOffset");
        Objects.requireNonNull(baseWallOffset, "baseWallOffset");
        Objects.requireNonNull(standardOffsetTransitionList, "standardOffsetTransitionList");
        Objects.requireNonNull(transitionList, "transitionList");
        Objects.requireNonNull(lastRules, "lastRules");
        return new ZoneRules(baseStandardOffset, baseWallOffset, standardOffsetTransitionList, transitionList, lastRules);
    }

    public static ZoneRules of(ZoneOffset offset) {
        Objects.requireNonNull(offset, "offset");
        return new ZoneRules(offset);
    }

    ZoneRules(ZoneOffset baseStandardOffset,
              ZoneOffset baseWallOffset,
              List<ZoneOffsetTransition> standardOffsetTransitionList,
              List<ZoneOffsetTransition> transitionList,
              List<ZoneOffsetTransitionRule> lastRules) {
        super();

        // convert standard transitions

        this.standardTransitions = new long[standardOffsetTransitionList.size()];

        this.standardOffsets = new ZoneOffset[standardOffsetTransitionList.size() + 1];
        this.standardOffsets[0] = baseStandardOffset;
        for (int i = 0; i < standardOffsetTransitionList.size(); i++) {
            this.standardTransitions[i] = standardOffsetTransitionList.get(i).toEpochSecond();
            this.standardOffsets[i + 1] = standardOffsetTransitionList.get(i).getOffsetAfter();
        }

        // convert savings transitions to locals
        List<LocalDateTime> localTransitionList = new ArrayList<>();
        List<ZoneOffset> localTransitionOffsetList = new ArrayList<>();
        localTransitionOffsetList.add(baseWallOffset);
        for (ZoneOffsetTransition trans : transitionList) {
            if (trans.isGap()) {
                localTransitionList.add(trans.getDateTimeBefore());
                localTransitionList.add(trans.getDateTimeAfter());
            } else {
                localTransitionList.add(trans.getDateTimeAfter());
                localTransitionList.add(trans.getDateTimeBefore());
            }
            localTransitionOffsetList.add(trans.getOffsetAfter());
        }
        this.savingsLocalTransitions = localTransitionList.toArray(new LocalDateTime[localTransitionList.size()]);
        this.wallOffsets = localTransitionOffsetList.toArray(new ZoneOffset[localTransitionOffsetList.size()]);

        // convert savings transitions to instants
        this.savingsInstantTransitions = new long[transitionList.size()];
        for (int i = 0; i < transitionList.size(); i++)
            this.savingsInstantTransitions[i] = transitionList.get(i).toEpochSecond();

        // last rules
        Object[] temp = lastRules.toArray();
        ZoneOffsetTransitionRule[] rulesArray = Arrays.copyOf(temp, temp.length, ZoneOffsetTransitionRule[].class);
        if (rulesArray.length > 16) {
            throw new IllegalArgumentException("Too many transition rules");
        }
        this.lastRules = rulesArray;
    }

    private ZoneRules(long[] standardTransitions,
                      ZoneOffset[] standardOffsets,
                      long[] savingsInstantTransitions,
                      ZoneOffset[] wallOffsets,
                      ZoneOffsetTransitionRule[] lastRules) {
        super();

        this.standardTransitions = standardTransitions;
        this.standardOffsets = standardOffsets;
        this.savingsInstantTransitions = savingsInstantTransitions;
        this.wallOffsets = wallOffsets;
        this.lastRules = lastRules;

        if (savingsInstantTransitions.length == 0) {
            this.savingsLocalTransitions = EMPTY_LDT_ARRAY;
        } else {
            // convert savings transitions to locals
            List<LocalDateTime> localTransitionList = new ArrayList<>();
            for (int i = 0; i < savingsInstantTransitions.length; i++) {
                ZoneOffset before = wallOffsets[i];
                ZoneOffset after = wallOffsets[i + 1];
                ZoneOffsetTransition trans = new ZoneOffsetTransition(savingsInstantTransitions[i], before, after);
                if (trans.isGap()) {
                    localTransitionList.add(trans.getDateTimeBefore());
                    localTransitionList.add(trans.getDateTimeAfter());
                } else {
                    localTransitionList.add(trans.getDateTimeAfter());
                    localTransitionList.add(trans.getDateTimeBefore());
                }
            }
            this.savingsLocalTransitions = localTransitionList.toArray(new LocalDateTime[localTransitionList.size()]);
        }
    }

    private ZoneRules(ZoneOffset offset) {
        this.standardOffsets = new ZoneOffset[1];
        this.standardOffsets[0] = offset;
        this.standardTransitions = EMPTY_LONG_ARRAY;
        this.savingsInstantTransitions = EMPTY_LONG_ARRAY;
        this.savingsLocalTransitions = EMPTY_LDT_ARRAY;
        this.wallOffsets = standardOffsets;
        this.lastRules = EMPTY_LASTRULES;
    }

    private void readObject(ObjectInputStream s) throws InvalidObjectException {
        throw new InvalidObjectException("Deserialization via serialization delegate");
    }

    private Object writeReplace() {
        return new Ser(Ser.ZRULES, this);
    }

    void writeExternal(DataOutput out) throws IOException {
        out.writeInt(standardTransitions.length);
        for (long trans : standardTransitions)
            Ser.writeEpochSec(trans, out);
        for (ZoneOffset offset : standardOffsets)
            Ser.writeOffset(offset, out);
        out.writeInt(savingsInstantTransitions.length);
        for (long trans : savingsInstantTransitions)
            Ser.writeEpochSec(trans, out);
        for (ZoneOffset offset : wallOffsets)
            Ser.writeOffset(offset, out);
        out.writeByte(lastRules.length);
        for (ZoneOffsetTransitionRule rule : lastRules) {
            rule.writeExternal(out);
        }
    }

    static ZoneRules readExternal(DataInput in) throws IOException, ClassNotFoundException {
        int stdSize = in.readInt();
        if (stdSize > 1024)
            throw new InvalidObjectException("Too many transitions");

        long[] stdTrans = (stdSize == 0) ? EMPTY_LONG_ARRAY : new long[stdSize];
        for (int i = 0; i < stdSize; i++)
            stdTrans[i] = Ser.readEpochSec(in);

        ZoneOffset[] stdOffsets = new ZoneOffset[stdSize + 1];
        for (int i = 0; i < stdOffsets.length; i++)
            stdOffsets[i] = Ser.readOffset(in);

        int savSize = in.readInt();
        if (savSize > 1024)
            throw new InvalidObjectException("Too many saving offsets");

        long[] savTrans = (savSize == 0) ? EMPTY_LONG_ARRAY
                : new long[savSize];
        for (int i = 0; i < savSize; i++)
            savTrans[i] = Ser.readEpochSec(in);

        ZoneOffset[] savOffsets = new ZoneOffset[savSize + 1];
        for (int i = 0; i < savOffsets.length; i++)
            savOffsets[i] = Ser.readOffset(in);

        int ruleSize = in.readByte();
        if (ruleSize > 16)
            throw new InvalidObjectException("Too many transition rules");

        ZoneOffsetTransitionRule[] rules = (ruleSize == 0) ? EMPTY_LASTRULES : new ZoneOffsetTransitionRule[ruleSize];
        for (int i = 0; i < ruleSize; i++)
            rules[i] = ZoneOffsetTransitionRule.readExternal(in);

        return new ZoneRules(stdTrans, stdOffsets, savTrans, savOffsets, rules);
    }

    public boolean isFixedOffset() {
        return standardOffsets[0].equals(wallOffsets[0]) &&
                standardTransitions.length == 0 &&
                savingsInstantTransitions.length == 0 &&
                lastRules.length == 0;
    }

    public ZoneOffset getOffset(Instant instant) {
        if (savingsInstantTransitions.length == 0)
            return wallOffsets[0];

        long epochSec = instant.getEpochSecond();
        // check if using last rules
        if (lastRules.length > 0 && epochSec > savingsInstantTransitions[savingsInstantTransitions.length - 1]) {
            int year = findYear(epochSec, wallOffsets[wallOffsets.length - 1]);
            ZoneOffsetTransition[] transArray = findTransitionArray(year);
            ZoneOffsetTransition trans = null;
            for (int i = 0; i < transArray.length; i++) {
                trans = transArray[i];
                if (epochSec < trans.toEpochSecond())
                    return trans.getOffsetBefore();
            }
            return trans.getOffsetAfter();
        }

        // using historic rules
        int index  = Arrays.binarySearch(savingsInstantTransitions, epochSec);
        if (index < 0) {
            // switch negative insert position to start of matched range
            index = -index - 2;
        }
        return wallOffsets[index + 1];
    }

    public ZoneOffset getOffset(LocalDateTime localDateTime) {
        Object info = getOffsetInfo(localDateTime);
        if (info instanceof ZoneOffsetTransition)
            return ((ZoneOffsetTransition) info).getOffsetBefore();
        return (ZoneOffset) info;
    }

    public List<ZoneOffset> getValidOffsets(LocalDateTime localDateTime) {
        // should probably be optimized
        Object info = getOffsetInfo(localDateTime);
        if (info instanceof ZoneOffsetTransition)
            return ((ZoneOffsetTransition) info).getValidOffsets();
        return Collections.singletonList((ZoneOffset) info);
    }

    public ZoneOffsetTransition getTransition(LocalDateTime localDateTime) {
        Object info = getOffsetInfo(localDateTime);
        return (info instanceof ZoneOffsetTransition ? (ZoneOffsetTransition) info : null);
    }

    private Object getOffsetInfo(LocalDateTime dt) {
        if (savingsLocalTransitions.length == 0)
            return wallOffsets[0];

        // check if using last rules
        if (lastRules.length > 0 &&
                dt.isAfter(savingsLocalTransitions[savingsLocalTransitions.length - 1])) {
            ZoneOffsetTransition[] transArray = findTransitionArray(dt.getYear());
            Object info = null;
            for (ZoneOffsetTransition trans : transArray) {
                info = findOffsetInfo(dt, trans);
                if (info instanceof ZoneOffsetTransition || info.equals(trans.getOffsetBefore()))
                    return info;
            }
            return info;
        }

        // using historic rules
        int index  = Arrays.binarySearch(savingsLocalTransitions, dt);
        if (index == -1) {
            // before first transition
            return wallOffsets[0];
        }
        if (index < 0) {
            // switch negative insert position to start of matched range
            index = -index - 2;
        } else if (index < savingsLocalTransitions.length - 1 &&
                savingsLocalTransitions[index].equals(savingsLocalTransitions[index + 1])) {
            // handle overlap immediately following gap
            index++;
        }
        if ((index & 1) == 0) {
            // gap or overlap
            LocalDateTime dtBefore = savingsLocalTransitions[index];
            LocalDateTime dtAfter = savingsLocalTransitions[index + 1];
            ZoneOffset offsetBefore = wallOffsets[index / 2];
            ZoneOffset offsetAfter = wallOffsets[index / 2 + 1];
            if (offsetAfter.getTotalSeconds() > offsetBefore.getTotalSeconds()) {
                // gap
                return new ZoneOffsetTransition(dtBefore, offsetBefore, offsetAfter);
            } else {
                // overlap
                return new ZoneOffsetTransition(dtAfter, offsetBefore, offsetAfter);
            }
        } else {
            // normal (neither gap or overlap)
            return wallOffsets[index / 2 + 1];
        }
    }

    private Object findOffsetInfo(LocalDateTime dt, ZoneOffsetTransition trans) {
        LocalDateTime localTransition = trans.getDateTimeBefore();
        if (trans.isGap()) {
            if (dt.isBefore(localTransition))
                return trans.getOffsetBefore();
            if (dt.isBefore(trans.getDateTimeAfter()))
                return trans;
            else
                return trans.getOffsetAfter();
        } else {
            if (dt.isBefore(localTransition) == false)
                return trans.getOffsetAfter();
            if (dt.isBefore(trans.getDateTimeAfter()))
                return trans.getOffsetBefore();
            else
                return trans;
        }
    }

    private ZoneOffsetTransition[] findTransitionArray(int year) {
        Integer yearObj = year;  // should use Year class, but this saves a class load
        ZoneOffsetTransition[] transArray = lastRulesCache.get(yearObj);
        if (transArray != null)
            return transArray;
        ZoneOffsetTransitionRule[] ruleArray = lastRules;
        transArray  = new ZoneOffsetTransition[ruleArray.length];
        for (int i = 0; i < ruleArray.length; i++)
            transArray[i] = ruleArray[i].createTransition(year);
        if (year < LAST_CACHED_YEAR)
            lastRulesCache.putIfAbsent(yearObj, transArray);
        return transArray;
    }

    public ZoneOffset getStandardOffset(Instant instant) {
        if (standardTransitions.length == 0)
            return standardOffsets[0];
        long epochSec = instant.getEpochSecond();
        int index  = Arrays.binarySearch(standardTransitions, epochSec);
        if (index < 0) {
            // switch negative insert position to start of matched range
            index = -index - 2;
        }
        return standardOffsets[index + 1];
    }

    public Duration getDaylightSavings(Instant instant) {
        if (isFixedOffset())
            return Duration.ZERO;
        ZoneOffset standardOffset = getStandardOffset(instant);
        ZoneOffset actualOffset = getOffset(instant);
        return Duration.ofSeconds(actualOffset.getTotalSeconds() - standardOffset.getTotalSeconds());
    }

    public boolean isDaylightSavings(Instant instant) {
        return (getStandardOffset(instant).equals(getOffset(instant)) == false);
    }

    public boolean isValidOffset(LocalDateTime localDateTime, ZoneOffset offset) {
        return getValidOffsets(localDateTime).contains(offset);
    }

    public ZoneOffsetTransition nextTransition(Instant instant) {
        if (savingsInstantTransitions.length == 0)
            return null;
        long epochSec = instant.getEpochSecond();
        // check if using last rules
        if (epochSec >= savingsInstantTransitions[savingsInstantTransitions.length - 1]) {
            if (lastRules.length == 0)
                return null;
            // search year the instant is in
            int year = findYear(epochSec, wallOffsets[wallOffsets.length - 1]);
            ZoneOffsetTransition[] transArray = findTransitionArray(year);
            for (ZoneOffsetTransition trans : transArray) {
                if (epochSec < trans.toEpochSecond()) {
                    return trans;
                }
            }
            // use first from following year
            if (year < Year.MAX_VALUE) {
                transArray = findTransitionArray(year + 1);
                return transArray[0];
            }
            return null;
        }

        // using historic rules
        int index  = Arrays.binarySearch(savingsInstantTransitions, epochSec);
        if (index < 0)
            index = -index - 1;  // switched value is the next transition
        else
            index += 1;  // exact match, so need to add one to get the next
        return new ZoneOffsetTransition(savingsInstantTransitions[index], wallOffsets[index], wallOffsets[index + 1]);
    }

    public ZoneOffsetTransition previousTransition(Instant instant) {
        if (savingsInstantTransitions.length == 0)
            return null;
        long epochSec = instant.getEpochSecond();
        if (instant.getNano() > 0 && epochSec < Long.MAX_VALUE)
            epochSec += 1;  // allow rest of method to only use seconds

        // check if using last rules
        long lastHistoric = savingsInstantTransitions[savingsInstantTransitions.length - 1];
        if (lastRules.length > 0 && epochSec > lastHistoric) {
            // search year the instant is in
            ZoneOffset lastHistoricOffset = wallOffsets[wallOffsets.length - 1];
            int year = findYear(epochSec, lastHistoricOffset);
            ZoneOffsetTransition[] transArray = findTransitionArray(year);
            for (int i = transArray.length - 1; i >= 0; i--) {
                if (epochSec > transArray[i].toEpochSecond())
                    return transArray[i];
            }
            // use last from preceding year
            int lastHistoricYear = findYear(lastHistoric, lastHistoricOffset);
            if (--year > lastHistoricYear) {
                transArray = findTransitionArray(year);
                return transArray[transArray.length - 1];
            }
            // drop through
        }

        // using historic rules
        int index  = Arrays.binarySearch(savingsInstantTransitions, epochSec);
        if (index < 0)
            index = -index - 1;
        if (index <= 0)
            return null;
        return new ZoneOffsetTransition(savingsInstantTransitions[index - 1], wallOffsets[index - 1], wallOffsets[index]);
    }

    private int findYear(long epochSecond, ZoneOffset offset) {
        long localSecond = epochSecond + offset.getTotalSeconds();
        long zeroDay = Math.floorDiv(localSecond, 86400) + DAYS_0000_TO_1970;

        // find the march-based year
        zeroDay -= 60;  // adjust to 0000-03-01 so leap day is at end of four year cycle
        long adjust = 0;
        if (zeroDay < 0) {
            // adjust negative years to positive for calculation
            long adjustCycles = (zeroDay + 1) / DAYS_PER_CYCLE - 1;
            adjust = adjustCycles * 400;
            zeroDay += -adjustCycles * DAYS_PER_CYCLE;
        }
        long yearEst = (400 * zeroDay + 591) / DAYS_PER_CYCLE;
        long doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400);
        if (doyEst < 0) {
            // fix estimate
            yearEst--;
            doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400);
        }
        yearEst += adjust;  // reset any negative year
        int marchDoy0 = (int) doyEst;

        // convert march-based values back to january-based
        int marchMonth0 = (marchDoy0 * 5 + 2) / 153;
        yearEst += marchMonth0 / 10;

        // Cap to the max value
        return (int)Math.min(yearEst, Year.MAX_VALUE);
    }

    public List<ZoneOffsetTransition> getTransitions() {
        List<ZoneOffsetTransition> list = new ArrayList<>();
        for (int i = 0; i < savingsInstantTransitions.length; i++)
            list.add(new ZoneOffsetTransition(savingsInstantTransitions[i], wallOffsets[i], wallOffsets[i + 1]));
        return Collections.unmodifiableList(list);
    }

    public List<ZoneOffsetTransitionRule> getTransitionRules() {
        return List.of(lastRules);
    }

    @Override
    public boolean equals(Object otherRules) {
        if (this == otherRules)
            return true;
        return (otherRules instanceof ZoneRules other)
                && Arrays.equals(standardTransitions, other.standardTransitions)
                && Arrays.equals(standardOffsets, other.standardOffsets)
                && Arrays.equals(savingsInstantTransitions, other.savingsInstantTransitions)
                && Arrays.equals(wallOffsets, other.wallOffsets)
                && Arrays.equals(lastRules, other.lastRules);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(standardTransitions) ^
                Arrays.hashCode(standardOffsets) ^
                Arrays.hashCode(savingsInstantTransitions) ^
                Arrays.hashCode(wallOffsets) ^
                Arrays.hashCode(lastRules);
    }

    @Override
    public String toString() {
        return "ZoneRules[currentStandardOffset=" + standardOffsets[standardOffsets.length - 1] + "]";
    }
}
