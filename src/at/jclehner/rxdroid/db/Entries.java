/**
 * Copyright (C) 2011, 2012 Joseph Lehner <joseph.c.lehner@gmail.com>
 *
 * This file is part of RxDroid.
 *
 * RxDroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RxDroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RxDroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */

package at.jclehner.rxdroid.db;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import at.jclehner.rxdroid.Fraction;
import at.jclehner.rxdroid.Fraction.MutableFraction;
import at.jclehner.rxdroid.util.Constants;
import at.jclehner.rxdroid.util.DateTime;

public final class Entries
{
	@SuppressWarnings("unused")
	private static final String TAG = Entries.class.getName();

	private static final String[] TIME_NAMES = {
		"MORNING", "NOON", "EVENING", "NIGHT"
	};

	public static List<Drug> getAllDrugs(int patientId)
	{
		final List<Drug> list = new ArrayList<Drug>();

		for(Drug drug : Database.getCached(Drug.class))
		{
			final boolean matches;

			if(patientId == 0)
				matches = (drug.getPatient() == null || drug.getPatient().isDefaultPatient());
			else
				matches = (patientId == drug.getPatientId());

			if(matches)
				list.add(drug);
		}

		return list;
	}

	public static CharSequence[] getAllPatientNames()
	{
		final List<Patient> patients = Database.getCached(Patient.class);
		final String[] names = new String[patients.size()];

		for(int i = 0; i != names.length; ++i)
			names[i] = patients.get(i).getName();

		return names;
	}

	public static boolean hasMissingIntakesBeforeDate(Drug drug, Date date)
	{
		if(!drug.isActive())
			return false;

		final Date lastScheduleUpdateDate = drug.getLastScheduleUpdateDate();
		if(lastScheduleUpdateDate != null && date.before(lastScheduleUpdateDate))
			return false;

		final int repeatMode = drug.getRepeatMode();
		switch(repeatMode)
		{
			case Drug.REPEAT_EVERY_N_DAYS:
			case Drug.REPEAT_WEEKDAYS:
			{
				if(drug.hasDoseOnDate(date))
					return false;
			}
		}

		if(repeatMode == Drug.REPEAT_EVERY_N_DAYS)
		{
			long days = drug.getRepeatArg();

			final Date origin = drug.getRepeatOrigin();
			if(date.before(origin))
				return false;

			long elapsedDays = (date.getTime() - origin.getTime()) / Constants.MILLIS_PER_DAY;

			int offset = (int) -(elapsedDays % days);
			if(offset == 0)
				offset = (int) -days;

			final Date lastIntakeDate = DateTime.add(date, Calendar.DAY_OF_MONTH, offset);
			if(!isDateAfterLastScheduleUpdateOfDrug(lastIntakeDate, drug))
				return false;

			return !hasAllIntakes(drug, lastIntakeDate);
		}
		else if(repeatMode == Drug.REPEAT_WEEKDAYS)
		{
			// FIXME this may fail to report a missed Intake if
			// the dose was taken on a later date (i.e. unscheduled)
			// in the week before.

			int expectedIntakeCount = 0;
			int actualScheduledIntakeCount = 0;

			for(int i = 0; i != 7; ++i)
			{
				final Date checkDate = DateTime.add(date, Calendar.DAY_OF_MONTH, -7 + i);
				if(!isDateAfterLastScheduleUpdateOfDrug(checkDate, drug))
					continue;

				for(int doseTime : Constants.DOSE_TIMES)
				{
					if(!drug.getDose(doseTime, checkDate).isZero())
					{
						++expectedIntakeCount;
						actualScheduledIntakeCount += countIntakes(drug, checkDate, doseTime);
					}
				}
			}

			return actualScheduledIntakeCount < expectedIntakeCount;
		}

		return false;
	}

	/**
	 * Get the number of days the drug's supply will last.
	 */
	public static int getSupplyDaysLeftForDrug(Drug drug, Date date)
	{
		// TODO this function currently does not take into account doses
		// that were taken after the specified date.

		if(date == null)
			date = DateTime.today();

		final MutableFraction doseLeftOnDate = new MutableFraction();

		if(date.equals(DateTime.today()) && drug.hasDoseOnDate(date))
		{
			for(int doseTime : Constants.DOSE_TIMES)
			{
				if(countIntakes(drug, date, doseTime) == 0)
					doseLeftOnDate.add(drug.getDose(doseTime, date));
			}
		}

		final double supply = drug.getCurrentSupply().doubleValue() - doseLeftOnDate.doubleValue();
		return (int) (Math.floor(supply / getDailyDose(drug) * getSupplyCorrectionFactor(drug)));
	}

	public static<T extends Entry> T findInCollectionById(Collection<T> collection, int id)
	{
		for(T t : collection)
		{
			if(t.getId() == id)
				return t;
		}

		return null;
	}

	/**
	 * Find all intakes meeting the specified criteria.
	 * <p>
	 * @param drug The drug to search for (based on its database ID).
	 * @param date The Intake's date. Can be <code>null</code>.
	 * @param doseTime The Intake's doseTime. Can be <code>null</code>.
	 */
	public static List<Intake> findIntakes(Drug drug, Date date, Integer doseTime)
	{
		final List<Intake> intakes = new LinkedList<Intake>();

		for(Intake intake : Database.getCached(Intake.class))
		{
			if(Intake.has(intake, drug, date, doseTime))
				intakes.add(intake);
		}

		return intakes;
	}

	public static int countIntakes(Drug drug, Date date, Integer doseTime) {
		return findIntakes(drug, date, doseTime).size();
	}

	public static boolean hasAllIntakes(Drug drug, Date date)
	{
		if(!drug.hasDoseOnDate(date))
			return true;

		//List<Intake> intakes = findAll(drug, date, null);
		for(int doseTime : Constants.DOSE_TIMES)
		{
			Fraction dose = drug.getDose(doseTime, date);
			if(!dose.isZero())
			{
				if(countIntakes(drug, date, doseTime) == 0)
					return false;
			}
		}

		return true;
	}

	public static String getDoseTimeString(int doseTime) {
		return TIME_NAMES[doseTime];
	}

	public static Fraction getTotalDoseInTimePeriod_dumb(Drug drug, Date begin, Date end, boolean stopIfSupplyIsEmpty)
	{
		final MutableFraction totalDose = new MutableFraction();

		final Calendar cal = DateTime.calendarFromDate(begin);
		cal.add(Calendar.DAY_OF_MONTH, 1);
		Date date;

		while((date = cal.getTime()).before(end) || date.equals(end))
		{
			getTotalDose(drug, date, totalDose);

			if(totalDose.isNegative() && stopIfSupplyIsEmpty)
				return drug.getCurrentSupply();

			cal.add(Calendar.DAY_OF_MONTH, 1);
		}

		return totalDose;
	}

	public static Fraction getTotalDoseInTimePeriod_smart(Drug drug, Date begin, Date end)
	{
		final int repeatMode = drug.getRepeatMode();
		final MutableFraction baseDose = new MutableFraction();
		int doseMultiplier = 0;

		if(repeatMode == Drug.REPEAT_ON_DEMAND)
			return Fraction.ZERO;
		if(repeatMode == Drug.REPEAT_DAILY)
		{
			getTotalDose(drug, null, baseDose);
			doseMultiplier = (int) DateTime.diffDays(begin, end);
		}
		else if(repeatMode == Drug.REPEAT_EVERY_N_DAYS)
		{
			getTotalDose(drug, null, baseDose);

			final long arg = drug.getRepeatArg();
			final long daysInPeriod = DateTime.diffDays(begin, end);

			if(drug.hasDoseOnDate(begin) || drug.hasDoseOnDate(end))
				doseMultiplier = 1;

			doseMultiplier += daysInPeriod / arg;
		}
		else if(repeatMode == Drug.REPEAT_WEEKDAYS)
		{
			getTotalDose(drug, null, new MutableFraction());

			final Calendar cal = DateTime.calendarFromDate(begin);
			int weekDay;

			// Manually check the days before the first Monday
			while((weekDay = cal.get(Calendar.DAY_OF_WEEK)) != Calendar.MONDAY)
			{
				if(drug.hasDoseOnWeekday(weekDay))
					++doseMultiplier;

				cal.add(Calendar.DAY_OF_WEEK, 1);
			}

			final Date firstMonday = cal.getTime();

			// Now check the days after the last Sunday
			cal.setTime(end);

			while((weekDay = cal.get(Calendar.DAY_OF_WEEK)) != Calendar.MONDAY)
			{
				if(drug.hasDoseOnWeekday(weekDay))
					++doseMultiplier;

				cal.add(Calendar.DAY_OF_WEEK, -1);
			}

			final Date lastSunday = cal.getTime();

			// Now comes the easy part: dealing with the full week(s) in between
			final long days = DateTime.diffDays(firstMonday, lastSunday);
			if(days > 7)
			{
				if(days % 7 != 0)
					throw new IllegalStateException("Not a full week: " + firstMonday + " - " + lastSunday);

				doseMultiplier += days / 7 * Long.bitCount(drug.getRepeatArg());
			}
		}
		else if(repeatMode == Drug.REPEAT_21_7)
		{
			getTotalDose(drug, null, baseDose);

			final Date origin = drug.getRepeatOrigin();

			long daysInTimePeriod = DateTime.diffDays(begin, end);
			final long daysFromOriginToBegin = DateTime.diffDays(origin, begin);
			final long daysFromOriginToEnd = DateTime.diffDays(origin, end);

			long index = daysFromOriginToBegin % 28;
			if(index < 21)
			{
				final long days = 21 - index;
				daysInTimePeriod -= days + 7;
				doseMultiplier += days;
			}

			index = daysFromOriginToEnd % 28;
			if(index < 21)
			{
				final long days = 21 - index;
				daysInTimePeriod -= days + 7;
				doseMultiplier += days;
			}

			if(daysInTimePeriod > 28)
				doseMultiplier += (daysInTimePeriod * 3) / 4;
		}
		else
			throw new UnsupportedOperationException();

		return baseDose.times(doseMultiplier);
	}

	public static boolean isDateAfterLastScheduleUpdateOfDrug(Date date, Drug drug)
	{
		final Date lastScheduleUpdateDate = drug.getLastScheduleUpdateDate();
		if(lastScheduleUpdateDate == null)
			return true;

		return date.after(lastScheduleUpdateDate);
	}

	private static void getTotalDose(Drug drug, Date date, MutableFraction outTotalDose)
	{
		if(date != null && !drug.hasDoseOnDate(date))
			return;

		final int repeatMode = drug.getRepeatMode();
		if(repeatMode == Drug.REPEAT_ON_DEMAND)
			return;

		for(int doseTime : Constants.DOSE_TIMES)
		{
			final Fraction dose;

			if(date == null)
				dose = drug.getDose(doseTime);
			else
				dose = drug.getDose(doseTime, date);

			outTotalDose.add(dose);
		}

		return;
	}

	private static double getDailyDose(Drug drug)
	{
		double dailyDose = 0.0;
		for(int doseTime : Constants.DOSE_TIMES)
			dailyDose += drug.getDose(doseTime).doubleValue();
		return dailyDose;
	}

	private static double getSupplyCorrectionFactor(Drug drug)
	{
		switch(drug.getRepeatMode())
		{
			case Drug.REPEAT_EVERY_N_DAYS:
				return drug.getRepeatArg();

			case Drug.REPEAT_WEEKDAYS:
				return 7.0 / Long.bitCount(drug.getRepeatArg());

			case Drug.REPEAT_21_7:
				return 1.0 / 0.75;

			default:
				return 1.0;
		}
	}

	private Entries() {}
}
