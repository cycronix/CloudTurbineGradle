package cycronix.ctlib;

//---------------------------------------------------------------------------------	
// CTdata: ArrayList of time,data couples
// Matt Miller, Cycronix
// 02/18/2014

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;

/**
 * CloudTurbine utility class to manage ArrayList of time,data couples
 * <p>
 * @author Matt Miller (MJM), Cycronix
 * @version 2014/03/06
 * 
*/

/*
* Copyright 2014 Cycronix
* All Rights Reserved
*
*   Date      By	Description
* MM/DD/YYYY
* ----------  --	-----------
* 02/06/2014  MJM	Created.
*/

// CTdata:  ArrayList of time,data couples
public class CTdata {
	private ArrayList<Double>timelist=new ArrayList<Double>();
	private ArrayList<byte[]>datalist=new ArrayList<byte[]>();
	private ArrayList<CTFile>filelist=new ArrayList<CTFile>();

	private java.nio.ByteOrder border = java.nio.ByteOrder.LITTLE_ENDIAN;	// Intel order (default, most common)
		
	CTdata() {}

	CTdata(double mytime, byte[] mydata) {		// mytime units = full-seconds
		this.add(mytime, mydata);				// mydata = full CT file worth each datalist element
	}
	
	CTdata(double mytime, byte[] mydata, CTFile file) {	
		this.add(mytime, mydata, file);
	}
	
	public void setSwap(boolean swap) {
		if(swap) 	border = java.nio.ByteOrder.BIG_ENDIAN;			// Java (non-Intel) order
		else 		border = java.nio.ByteOrder.LITTLE_ENDIAN;		// Intel order (default, most common)
	}
	
	@Deprecated
	public void setDebug(boolean idebug) {
		CTinfo.setDebug(idebug);
//		debug = idebug;
	}
	
	// add byte array
	void add(double mytime, byte[] mydata) {
		timelist.add(mytime);
		datalist.add(mydata);
	}
	
	// add byte array
	void add(double mytime, byte[] mydata, CTFile file) {
		timelist.add(mytime);
		datalist.add(mydata);
		filelist.add(file);
	}
	
	// add CTFile (unfinished, for delayed data-read concept)
	void add(double mytime, CTFile myfile) {
		timelist.add(mytime);
		filelist.add(myfile);
	}
	
	void add(CTdata tdata) {		// append 
		timelist.addAll(tdata.timelist);
		datalist.addAll(tdata.datalist);
		filelist.addAll(tdata.filelist);
	}
	
	public int size() {	return datalist.size(); }
	
	public double[] getTime() { 
		double mytime[] = new double[timelist.size()];
		for(int i=0; i< mytime.length; i++) mytime[i] = timelist.get(i);
		return mytime;
	}
	
//-----------------------------------------------------------------------------------------------------------------------------
// get subset of data for time interval, expanding times across packed byte array
	CTdata timeRange(double start, double duration) { 
		return timeRange(1, 0., start, duration, "absolute");
	}
	
	CTdata timeRange(int wordsize, double start, double duration, String tmode) { 
		return timeRange(wordsize, 0., start, duration, tmode);
	}
	
	CTdata timeRange(int wordSize, double start, double duration) { 
//		if(wordSize <= 1) return this;								// pass through
		return timeRange(wordSize, 0., start, duration, "absolute");			// derive timeInc
	}
	
	CTdata timeRange(char wordType, double start, double duration, String tmode) { 
		switch(wordType) {
			case 'n':
			case 'N':
				return timeRangeNumeric(wordType, start, duration, tmode);
			default:
				return timeRange(CTinfo.wordSize(wordType), 0., start, duration, tmode);
		}
	}

	//-----------------------------------------------------------------------------------------------------------------------------
	// special timeRange method to handle "numeric" (string-numbers) data
	// TODO:  consolidate timeRangeNumeric() with timeRange() methods:
	//			- generalize header logic (.wav etc)
	//			- generalize extract String[] vs  byte[] chunks
	//			- incTimeI is defunct?
	
	CTdata timeRangeNumeric(int wordSize, double start, double duration, String tmode) { 
		// Note:  start, duration is requested time, versus timelist being actual available time

		double tend = start + duration;		// for ref
		CTdata ctd = new CTdata();
		int nframe = datalist.size();
		
//		System.err.println("requested tstart: "+start+", requested duration: "+duration+", tend: "+tend);
		double Tprev=0;	String Dprev="";		// for duration=0 previous-Point
		
		for(int i=0; i<nframe; i++) {
			double tbase = filelist.get(i).baseTime();
			double time = timelist.get(i);
			
			CTinfo.debugPrint("filelist.get("+i+"): "+filelist.get(i).getMyPath()+", tbase: "+tbase+", time: "+time);
			
			String dds = new String(datalist.get(i));		// CSV comma separated string of values
			String[] ddp = dds.split(",");
			long count = ddp.length;

			double dt = 0;
			if(count>1) {
				dt = (time-tbase) / (count-1);		// timelist(i) is point-time (block end time)
//				time -= dt*(count-1);				// adjust to start of multi-point block
				time = tbase;						// start at tref=0, stop 1dt short
			}
			
			// not sure all the next/prev/oldest/newest logic is in here...
			CTinfo.debugPrint("time("+i+"): "+time+", data: "+dds+", dt: "+dt+", count: "+count);

			if(i==0) { Tprev = time; Dprev=ddp[0]; }	// initialize
			for(int j=0; j<count; j++) {
				double t = time+j*dt;
//				System.err.println("t["+j+"]: "+t);
				if((t>tend && duration==0)) {		// single-point case
					ctd.add(Tprev, Dprev.getBytes());
//					System.err.println("d=0 grab t: "+t+", ptime: "+Tprev+", d: "+Dprev+", j: "+j+", count: "+count);
				}
				else if(t>=start && t<=tend) {		// trim to requested start+duration
					double ptime = time+j*dt;
					ctd.add(ptime, ddp[j].getBytes());
//					System.err.println("grab t: "+t+", ptime: "+ptime+", d: "+ddp[j]+", date: "+new Date((long)(t*1000)));
				}
				Tprev = t;				// save previous 
				Dprev = ddp[j];
				
				if(t >= tend) break;
			}
			if(time >= tend) break;
		}
		
		return ctd;
	}
	
	//-----------------------------------------------------------------------------------------------------------------------------
	// timeRange from binary data
	CTdata timeRange(int wordSize, double incTimeI, double start, double duration, String tmode) { 
		CTdata ctd = new CTdata();
		double incTime = incTimeI;
		double prevtime=0;

		if(tmode.equals("newest")) start = timelist.get(timelist.size()-1) - duration;		
//		if(tmode.equals("oldest")) start = timelist.get(0);		// defer until find first point, may need to deduct block-duration 
		
		double end = start + duration;		// presume absolute time provided
		CTinfo.debugPrint("timeRange, start: "+start+", end: "+end+", duration: "+duration+", timelist(0): "+timelist.get(0)+", wordSize: "+wordSize);
	
		if(start==0. || duration < 0.) end = Double.MAX_VALUE;		// use full range if relative timestamp 
		int nframe = datalist.size();

		boolean deduceIncTime=true;		// obsolete?  still used for audio.pcm, but audio plays, scrolling is glumpy...	
		if(deduceIncTime) {
			// deduce incremental point times within packed binary frame as average over frame time(s)
			incTime = incTimeI;			// use by default
			if(wordSize>1 && incTimeI == 0. && nframe > 1) {	// old-fashioned way to preserve old multi-block PCM CTdata?
				int count=0;
				prevtime = 0;
				for(int i=0; i<(nframe-1); i++) {
					double time = timelist.get(i);			// skip dupes
					if(time == prevtime) continue;
					prevtime = time; 
					if(datalist.get(i) != null)					// bleh
						count += datalist.get(i).length;		// count all points (less last frame)
					//				System.err.println("frame time["+i+"]: "+timelist.get(i));
				}
				count /= wordSize;
				double timerange = timelist.get(nframe-1) - timelist.get(0);
				incTime =  timerange / count;	// avg all frames
				//			System.err.println("timeRangeNM1: "+timelist.get(nframe-1)+", nframe: "+nframe+", incTime: "+incTime+", count: "+count+", timerange: "+timerange);
			}
			if(wordSize>1 && incTime == 0. && filelist.size() < nframe) {		// can happen with single nframe==1 (bleh)
				System.err.println("WARNING: cannot derive incremental point times, using constant over frame");
			}	
		}
		
		prevtime = 0;
		String oldZipFile=null;
		
		for(int i=0; i<nframe; i++) {					// multiple frames per arraylist element
			double time = timelist.get(i);

			CTinfo.debugPrint("frame: "+i+", tframe: "+time+", nframe: "+nframe+", start: "+start+", end: "+end);
			if(time == prevtime) continue;				// skip dupes		
			
			CTinfo.debugPrint("wordSize: "+wordSize+", duration: "+duration+", tmode: "+tmode+", i: "+i+", nframe: "+nframe+", time: "+time);

			if(wordSize <= 1) {							// full intact frames 
				if(tmode.equals("oldest") && start==0) { start = time; end = start + duration; }
				if(tmode.equals("prev")) { start = end; end = start - duration; }		// mjm 4/27/16
				
				if(time < start) continue;
				if(duration == 0) {						// single-point case, just return first one after start time
					CTinfo.debugPrint("duration 0, time: "+time+", start: "+start+", tmode: "+tmode);
					if(tmode.equals("next")) {
						if((time == start) && i<(nframe-1)) ctd.add(timelist.get(i+1), datalist.get(i+1));		
						else 								ctd.add(timelist.get(i), datalist.get(i));			// index "i" is one past start
					}
					else if(i==0 || (!tmode.equals("prev") && start==time))
															ctd.add(timelist.get(i), datalist.get(i));			// first is already past, just grab it
					else									ctd.add(timelist.get(i-1), datalist.get(i-1));		// index "i-1" is at or one before start
					break;
				} 

				if(time > end) break;
				
				ctd.add(time, datalist.get(i));
			}
			else {			//  multi-point blocks
				int waveHeader = filelist.get(i).getName().endsWith(".wav")?44:0;		// skip audio.wav header (44 bytes)
				byte[] data = datalist.get(i);
				int count = 0;
				if(data == null) {
					ctd.add(time, (byte[]) null);			// time-only request
					return ctd;
				}
				else count = (data.length-waveHeader)/wordSize;		// multiple words per frame
				double dt = 0.;
				double refTime = 0.;
				String thisZipFile=null;
				// see if can glean incTime from (filetime - ziptime)
				
				// first block in zip, use dt = blockTime - zipFileTime
				// subsequent blocks in zip, use dt = blockTime(i+1) - blockTime(i)
				// if dt==0, fall back to old average interval calc (above)
				if(wordSize>1 && filelist.size() >= nframe) {
					CTFile file = filelist.get(i);
					thisZipFile = file.getParent();			// could be zip or folder
					if(thisZipFile != null) {
						if(thisZipFile.equals(oldZipFile)) 	refTime = prevtime;						// multi-block per Zip
//						else								refTime = file.fileTime(thisZipFile);	// single (or first) block per Zip
						else								refTime = file.baseTime();

						if(refTime > 0) {
							double interval = time - refTime;
//							dt = interval / count;
							if(count>1) dt = interval / (count-1);		// block start-time at 0, end-time at Tblock*(n-1)/n
							CTinfo.debugPrint("thisZip: "+thisZipFile+", oldZipFile: "+oldZipFile+", refTime: "+refTime+", prevtime: "+prevtime+", interval: "+interval+", dt: "+dt);
						}
					}
				}
				// for priority of getting back-to-back data, use waveHeader as fall-back				
//				else 
				if(dt==0 && waveHeader > 0) {						// grab sampleRate out of audio.wav header if available
					int offset = 24;									// Wave header sampleRate entry location
					int value = (data[3+offset] << (Byte.SIZE * 3));
					value |= (data[2+offset] & 0xFF) << (Byte.SIZE * 2);
					value |= (data[1+offset] & 0xFF) << (Byte.SIZE * 1);
					value |= (data[0+offset] & 0xFF);
					dt = 1. / value;
//					time += dt*count;			// counter adjustment below?
					CTinfo.debugPrint("got WaveHeader! sampRate: "+value+", dt: "+dt+", totalcount: "+count);
				}
				
				if(dt == 0.) {
					dt = incTime;		// backup way for old multi-block PCM frames
					CTinfo.debugPrint("CTdata, blockdata zero zip-to-entry dt, using backup incTime: "+incTime);
				}
				if(dt == 0. && count > 1) System.err.println("Warning, using constant time over data block!");
				
//				CTinfo.debugPrint("time: "+time+", dt: "+dt+", adjTime: "+(time - dt*count));
//				double endBlock = time;		// for ref (round off error)
				
//				time -= dt*count;		// adjust to start of multi-point block
//				time -= dt*(count-1);	// adjust to start of multi-point block
				time = refTime;		// MJM 7/28/16:  blocks start at 0-relative time for consistency with non-block times
				
				if(tmode.equals("oldest") && start==0) { start = time; end = start + duration; }
				
				CTinfo.debugPrint("CTdata frame: "+i+", t1: "+time+", t2: "+(time+count*dt));
				for(int j=0; j<count; j++, time+=dt) {		// could jump ahead for "newest" and save some effort...
					CTinfo.debugPrint("count: "+count+", start: "+start+", end: "+end+", time: "+time+", j: "+j+", dt: "+dt);
//					if(j==(count-1)) time = endBlock;	// precisely get endtime, e.g. for newest 
					if(time < start) continue;
					else if(time <= end) {
						int idx = j + waveHeader/wordSize;
						byte[] barray = Arrays.copyOfRange(data, idx*wordSize, (idx+1)*wordSize);	// parse bytes into words
//						System.err.println("ctd add time<end");
						ctd.add(time, barray);
					}
					else if((duration == 0) && (time > end)) {		// special at-or-before
						int idx = (j>0)?(j-1):0;
						idx += waveHeader/wordSize;
						byte[] barray = Arrays.copyOfRange(data, idx*wordSize, (idx+1)*wordSize);
//						System.err.println("ctd add at-or-before");;
						ctd.add(time, barray);
					}
					
					if(time >= end) break;		// double-check t>end
				}
				oldZipFile = thisZipFile;		
			}
//			if(time >= end) break;		// double-check t>end
			if(time > end) break;		// double-check t>end

			prevtime = time; 
		}
		return ctd;
	}
	
//-----------------------------------------------------------------------------------------------------------------------------
// getData as various primitive types
	
	public double[] getDataAsFloat64() {
		int nword = datalist.size();
		int count = 0;
		for(int i=0; i<nword; i++) count += (datalist.get(i).length / 8);	// pre-pass count total
		double data[] = new double[count];
		for(int i=0, k=0; i<nword; i++) {		// multiple words per arraylist element
			count = datalist.get(i).length/8;
			for(int j=0; j<count; j++, k++) {
				data[k] = ByteBuffer.wrap(datalist.get(i), j*8, 8).order(border).getDouble();
			}
		}
		return data;
	}
	
	public float[] getDataAsFloat32() {
		int nword = datalist.size();
		int count = 0;
		for(int i=0; i<nword; i++) count += (datalist.get(i).length / 4);	// pre-pass count total
		float data[] = new float[count];
		for(int i=0, k=0; i<nword; i++) {		// multiple words per arraylist element
			count = datalist.get(i).length/4;
			for(int j=0; j<count; j++, k++) {
				data[k] = ByteBuffer.wrap(datalist.get(i), j*4, 4).order(border).getFloat();
			}
		}
		return data;
	}
	
	public long[] getDataAsInt64() {
		int nword = datalist.size();
		int count = 0;
		for(int i=0; i<nword; i++) count += (datalist.get(i).length / 8);	// pre-pass count total
		long data[] = new long[count];
		for(int i=0, k=0; i<nword; i++) {		// multiple words per arraylist element
			count = datalist.get(i).length/8;
			for(int j=0; j<count; j++, k++) {
				data[k] = ByteBuffer.wrap(datalist.get(i), j*8, 8).order(border).getLong();
			}
		}
		return data;
	}
	
	public int[] getDataAsInt32() {
		int nword = datalist.size();
		int count = 0;
		for(int i=0; i<nword; i++) count += (datalist.get(i).length / 4);	// pre-pass count total
		int data[] = new int[count];
		for(int i=0, k=0; i<nword; i++) {		// multiple words per arraylist element
			count = datalist.get(i).length/4;
			for(int j=0; j<count; j++, k++) {
				data[k] = ByteBuffer.wrap(datalist.get(i), j*4, 4).order(border).getInt();
			}
		}
		return data;
	}
	
	public short[] getDataAsInt16() {
		int nword = datalist.size();
		int count = 0;
		for(int i=0; i<nword; i++) count += (datalist.get(i).length / 2);	// pre-pass count total
		short data[] = new short[count];
//		System.err.println("getDataAsInt16, nword: "+nword);
		for(int i=0, k=0; i<nword; i++) {		// multiple words per arraylist element
			count = datalist.get(i).length/2;
			for(int j=0; j<count; j++, k++) {
				data[k] = ByteBuffer.wrap(datalist.get(i), j*2, 2).order(border).getShort();
			}
		}
		return data;
	}
	
	public double[] getDataAsNumericF64() {
		int nword = datalist.size();
		double data[] = new double[nword];		// presume 1 word per arraylist
		for(int i=0; i<nword; i++) {
			data[i] = Double.parseDouble(new String(datalist.get(i)));
		}
		return data;
	}
	
	public float[] getDataAsNumericF32() {
		int nword = datalist.size();
		float data[] = new float[nword];		// presume 1 word per arraylist
		for(int i=0; i<nword; i++) {
			data[i] = Float.parseFloat(new String(datalist.get(i)));
		}
		return data;
	}
	
	// get raw (byte[]) data
	public byte[][] getData() {
		int nword = datalist.size();
		byte[][] data = new byte[nword][];		// presume 1 element per arraylist
		for(int i=0; i<nword; i++) {
			data[i] = datalist.get(i);
		}
		return data;
	}
	
	// get any type of numeric data converted to string
	public String[] getDataAsString(char ftype) {
		
		int numData = datalist.size();
		String[] response = new String[numData];
		
		switch(ftype) {
		case 's':			// string text
			byte[][]Tdata = getData();
			for(int i=0; i<numData; i++) response[i] = new String(Tdata[i]);
			break;

		case 'N':
			double Ndata[] = getDataAsNumericF64();
			for(int i=0; i<numData; i++) response[i] = String.valueOf(Ndata[i]);
			break;

		case 'n':
			float ndata[] = getDataAsNumericF32();
			for(int i=0; i<numData; i++) response[i] = String.valueOf(ndata[i]);
			break;

		case 'I':
			long[] ldata = getDataAsInt64();
			for(int i=0; i<numData; i++) response[i] = String.valueOf(ldata[i]);						
			break;

		case 'i':
			int[] idata = getDataAsInt32();
			for(int i=0; i<numData; i++) response[i] = String.valueOf(idata[i]);
			break;

		case 'j':
			short[] sdata = getDataAsInt16();
			for(int i=0; i<numData; i++) response[i] = String.valueOf(sdata[i]);
			break;

		case 'F':	
			double ddata[] = getDataAsFloat64();
			for(int i=0; i<numData; i++) response[i] = String.valueOf(ddata[i]);
			break;

		case 'f':	
			float fdata[] = getDataAsFloat32();
			for(int i=0; i<numData; i++) response[i] = String.valueOf(fdata[i]);
			break;

		case 'b':		// don't convert binary data
		case 'B':           
		default:
			response = null;
			break;
		}
		
		return response;
	}
}
