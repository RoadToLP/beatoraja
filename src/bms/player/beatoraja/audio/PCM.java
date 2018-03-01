package bms.player.beatoraja.audio;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import javazoom.jl.decoder.*;

import com.badlogic.gdx.backends.lwjgl.audio.OggInputStream;
import com.badlogic.gdx.utils.StreamUtils;
import com.badlogic.gdx.utils.StreamUtils.OptimizedByteArrayOutputStream;;

/**
 * PCM音源処理用クラス
 * 
 * @author exch
 */
public class PCM {

	// TODO PCM実データのダイレクトバッファ化

	/**
	 * チャンネル数
	 */
	private int channels;
	/**
	 * 音源のサンプリングレート(Hz)
	 */
	private int sampleRate;
	/**
	 * PCMのタイプ
	 */
	private int type;
	/**
	 * サンプル当たりのビット数
	 */
	private int bitsPerSample;
	/**
	 * PCMデータ
	 */
	private short[] sample;
	
	private int start;
	
	private int len;

	private PCM() {

	}

	public PCM(Path p) throws IOException {
		// final long time = System.nanoTime();
		byte[] pcm = null;
		int bytes = 0;
		
		final String name = p.toString().toLowerCase();
		if (name.endsWith(".wav")) {
			try (WavInputStream input = new WavInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
				switch(type) {
				case 1:
				case 3:
					OptimizedByteArrayOutputStream output = new OptimizedByteArrayOutputStream(input.dataRemaining);
					StreamUtils.copyStream(input, output);
					pcm = output.getBuffer();
					bytes = output.size();
					break;
				case 85:
					try {
						pcm = decodeMP3(new ByteArrayInputStream(
								StreamUtils.copyStreamToByteArray(input, input.dataRemaining)));
						bytes = pcm.length;
					} catch (BitstreamException e) {
						e.printStackTrace();
					}
					break;
				default:
					throw new IOException(p.toString() + " unsupported WAV format ID : " + type);					
				}
			} catch (Throwable e) {
				Logger.getGlobal().warning("WAV処理中の例外 - file : " + p + " error : "+ e.getMessage());
			}
		} else if (name.endsWith(".ogg")) {
			try (OggInputStream input = new OggInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
				// final long time = System.nanoTime();
				// OptimizedByteArrayOutputStream output = new
				// OptimizedByteArrayOutputStream(4096);
				OptimizedByteArrayOutputStream output = new OptimizedByteArrayOutputStream(input.getLength() * 16);
				byte[] buff = new byte[4096];
				while (!input.atEnd()) {
					int length = input.read(buff);
					if (length == -1)
						break;
					output.write(buff, 0, length);
				}

				channels = input.getChannels();
				sampleRate = input.getSampleRate();
				bitsPerSample = 16;

				pcm = output.getBuffer();
				bytes = output.size();
				// System.out.println(p.toString() + " : " + (System.nanoTime()
				// - time));
			} catch (Throwable ex) {
			}
		} else if (name.endsWith(".mp3")) {
			try {
				pcm = decodeMP3(new BufferedInputStream(Files.newInputStream(p)));
				bytes = pcm.length;
			} catch (Throwable ex) {
			}
		}

		if(pcm == null) {
			throw new IOException(p.toString() + " : can't convert to PCM");			
		}		
		bytes = bytes - (bytes % (channels > 1 ? bitsPerSample / 4 : bitsPerSample / 8));
		final int orgbytes = bytes;
		while(bytes > channels * bitsPerSample / 8) {
			boolean zero = true;
			for(int i = 0;i < channels * bitsPerSample / 8;i++){
				zero &= (pcm[bytes - i - 1] == 0x00);
			}
			if(zero) {
				bytes -= channels * bitsPerSample / 8;
			} else {
				break;
			}
		}
		if(bytes != orgbytes) {
			Logger.getGlobal().info("終端の無音データ除外 - " + p.getFileName().toString() + " : " + (orgbytes - bytes) + " bytes");
		}
		if(bytes <= channels * bitsPerSample / 8) {
			throw new IOException(p.toString() + " : 0 samples");			
		}
		
//		 System.out.println(p.getFileName().toString() +
//		 " - PCM generated : " + bitsPerSample + "bit " + sampleRate
//		 + "Hz " + channels + "channel PCM type : " + type);

		switch(bitsPerSample) {
		case 8:
			this.sample = new short[bytes];
			for (int i = 0; i < sample.length; i++) {
				this.sample[i] = (short) ((((short) pcm[i]) - 128) * 256);
			}
			break;
		case 16:
			// final long time = System.nanoTime();
			this.sample = new short[bytes / 2];
			for (int i = 0; i < sample.length; i++) {
				this.sample[i] = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8));
			}

			// ShortBuffer shortbuf =
			// ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
			// shortbuf.get(sample);
			// System.out.println(p.toString() + " : " + (System.nanoTime()
			// - time));
			break;
		case 24:
			this.sample = new short[bytes / 3];
			for (int i = 0; i < this.sample.length; i++) {
				this.sample[i] = (short) ((pcm[i * 3 + 1] & 0xff) | (pcm[i * 3 + 2] << 8));
			}
			break;
		case 32:
			int pos = 0;
			this.sample = new short[bytes / 4];
			for (int i = 0; i < this.sample.length; i++) {
				this.sample[i] = (short) (Float.intBitsToFloat((pcm[pos] & 0xff) | ((pcm[pos + 1] & 0xff) << 8)
						| ((pcm[pos + 2] & 0xff) << 16) | ((pcm[pos + 3] & 0xff) << 24)) * Short.MAX_VALUE);
				pos += 4;
			}
			break;
		default:
			throw new IOException(p.toString() + " : " + bitsPerSample + " bits per samples isn't supported");			
		}
		start = 0;
		len = sample.length;
		// System.out.println(p.toString() + " : " + (System.nanoTime() -
		// time));
	}

	private byte[] decodeMP3(InputStream is) throws BitstreamException {
		Bitstream bitstream = new Bitstream(is);
		ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
		MP3Decoder decoder = new MP3Decoder();
		OutputBuffer outputBuffer = null;
		while (true) {
			Header header = bitstream.readFrame();
			if (header == null)
				break;
			if (outputBuffer == null) {
				channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
				outputBuffer = new OutputBuffer(channels, false);
				decoder.setOutputBuffer(outputBuffer);
				sampleRate = header.getSampleRate();
				bitsPerSample = 16;
			}
			try {
				decoder.decodeFrame(header, bitstream);
			} catch (Exception ignored) {
				// JLayer's decoder throws
				// ArrayIndexOutOfBoundsException
				// sometimes!?
			}
			bitstream.closeFrame();
			output.write(outputBuffer.getBuffer(), 0, outputBuffer.reset());
		}
		bitstream.close();
		return output.toByteArray();

	}

	public int getChannels() {
		return channels;
	}

	public int getSampleRate() {
		return sampleRate;
	}

	public int getType() {
		return type;
	}

	public int getBitsPerSample() {
		return bitsPerSample;
	}

	public short[] getSample() {
		return sample;
	}
	
	public int getStart() {
		return start;
	}

	public int getLength() {
		return len;
	}

	/**
	 * サンプリングレートを変更したPCMを返す
	 * 
	 * @param sample
	 *            サンプリングレート
	 * @return サンプリングレート変更後のPCM
	 */
	public PCM changeSampleRate(int sample) {
		PCM pcm = new PCM();
		pcm.channels = channels;
		pcm.bitsPerSample = bitsPerSample;
		pcm.sampleRate = sample;

		pcm.sample = new short[(int) (((long) this.sample.length / channels) * sample / sampleRate) * channels];

		for (long i = 0; i < pcm.sample.length / channels; i++) {
			for (int j = 0; j < channels; j++) {
				if ((i * sampleRate) % sample != 0
						&& (int) ((i * sampleRate / sample + 1) * channels + j) < this.sample.length) {
					pcm.sample[(int) (i * channels
							+ j)] = (short) (this.sample[(int) ((i * sampleRate / sample) * channels + j)] / 2
									+ this.sample[(int) ((i * sampleRate / sample + 1) * channels + j)] / 2);
				} else {
					pcm.sample[(int) (i * channels + j)] = this.sample[(int) ((i * sampleRate / sample) * channels
							+ j)];
				}
			}
		}
		pcm.start = 0;
		pcm.len = pcm.sample.length;
		return pcm;
	}

	/**
	 * 再生速度を変更したPCMを返す
	 * 
	 * @param rate
	 *            再生速度。基準は1.0
	 * @return 再生速度を変更したPCM
	 */
	public PCM changeFrequency(float rate) {
		PCM pcm = changeSampleRate((int) (sampleRate / rate));
		pcm.sampleRate = sampleRate;
		return pcm;
	}

	/**
	 * チャンネル数を変更したPCMを返す
	 * 
	 * @param channels
	 *            チャンネル数
	 * @return チャンネル数を変更したPCM
	 */
	public PCM changeChannels(int channels) {
		PCM pcm = new PCM();
		pcm.channels = channels;
		pcm.bitsPerSample = bitsPerSample;
		pcm.sampleRate = sampleRate;

		pcm.sample = new short[this.sample.length * channels / this.channels];

		for (long i = 0; i < pcm.sample.length / channels; i++) {
			for (int j = 0; j < channels; j++) {
				pcm.sample[(int) (i * channels + j)] = this.sample[(int) (i * this.channels)];
			}
		}
		pcm.start = 0;
		pcm.len = pcm.sample.length;
		return pcm;
	}

	/**
	 * トリミングしたPCMを返す
	 * 
	 * @param starttime
	 *            開始時間(us)
	 * @param duration
	 *            再生時間(us)
	 * @return トリミングしたPCM
	 */
	public PCM slice(long starttime, long duration) {
		PCM pcm = new PCM();
		pcm.channels = channels;
		pcm.bitsPerSample = bitsPerSample;
		pcm.sampleRate = sampleRate;

		if (duration == 0 || starttime + duration > ((long) this.sample.length) * 1000000 / (sampleRate * channels)) {
			duration = Math.max(((long) this.sample.length) * 1000000 / (sampleRate * channels) - starttime, 0);
		}

		final int start = (int) ((starttime * sampleRate / 1000000) * channels);
		int length = (int) ((duration * sampleRate / 1000000) * channels);
		final int orglength = length;
		while(length > channels) {
			boolean zero = true;
			for(int i = 0;i < channels;i++){
				zero &= (this.sample[start + length - i - 1] == 0);
			}
			if(zero) {
				length -= channels;
			} else {
				break;
			}
		}
		if(length != orglength) {
			Logger.getGlobal().info("終端の無音データ除外 - " + (orglength - length) + " samples");
		}

		pcm.sample = this.sample;
		pcm.start = start;
		pcm.len = length;
		return pcm;
	}

	public InputStream getInputStream() {
		return new WavFileInputStream(this);
	}

	/** @author Nathan Sweet */
	private class WavInputStream extends FilterInputStream {
		private int dataRemaining;

		WavInputStream(InputStream p) {
			super(p);
			try {
				if (read() != 'R' || read() != 'I' || read() != 'F' || read() != 'F')
					throw new RuntimeException("RIFF header not found: " + p.toString());

				skipFully(4);

				if (read() != 'W' || read() != 'A' || read() != 'V' || read() != 'E')
					throw new RuntimeException("Invalid wave file header: " + p.toString());

				int fmtChunkLength = seekToChunk('f', 'm', 't', ' ');

				type = read() & 0xff | (read() & 0xff) << 8;

				channels = read() & 0xff | (read() & 0xff) << 8;

				sampleRate = read() & 0xff | (read() & 0xff) << 8 | (read() & 0xff) << 16 | (read() & 0xff) << 24;

				skipFully(6);

				bitsPerSample = read() & 0xff | (read() & 0xff) << 8;

				skipFully(fmtChunkLength - 16);

				dataRemaining = seekToChunk('d', 'a', 't', 'a');
			} catch (Throwable ex) {
				StreamUtils.closeQuietly(this);
				throw new RuntimeException("Error reading WAV file: " + p.toString(), ex);
			}
		}

		private int seekToChunk(char c1, char c2, char c3, char c4) throws IOException {
			while (true) {
				boolean found = read() == c1;
				found &= read() == c2;
				found &= read() == c3;
				found &= read() == c4;
				int chunkLength = read() & 0xff | (read() & 0xff) << 8 | (read() & 0xff) << 16 | (read() & 0xff) << 24;
				if (chunkLength == -1)
					throw new IOException("Chunk not found: " + c1 + c2 + c3 + c4);
				if (found)
					return chunkLength;
				skipFully(chunkLength);
			}
		}

		private void skipFully(int count) throws IOException {
			while (count > 0) {
				long skipped = in.skip(count);
				if (skipped <= 0)
					throw new EOFException("Unable to skip.");
				count -= skipped;
			}
		}

		public int read(byte[] buffer) throws IOException {
			if (dataRemaining == 0)
				return -1;
			int length = Math.min(super.read(buffer), dataRemaining);
			if (length == -1)
				return -1;
			dataRemaining -= length;
			return length;
		}
	}
	
	static class WavFileInputStream extends InputStream {

		private int pos = 0;
		private int mark = 0;
		private final byte[] header;
		private final PCM pcm;

		public WavFileInputStream(PCM pcm) {
			header = new byte[44];

			final int sampleRate = pcm.getSampleRate();
			final int channels = pcm.getChannels();
			this.pcm = pcm;
			final long totalDataLen = pcm.len * 2 + 36;
			final long bitrate = sampleRate * channels * 16;

			header[0] = 'R';
			header[1] = 'I';
			header[2] = 'F';
			header[3] = 'F';
			header[4] = (byte) (totalDataLen & 0xff);
			header[5] = (byte) ((totalDataLen >> 8) & 0xff);
			header[6] = (byte) ((totalDataLen >> 16) & 0xff);
			header[7] = (byte) ((totalDataLen >> 24) & 0xff);
			header[8] = 'W';
			header[9] = 'A';
			header[10] = 'V';
			header[11] = 'E';
			header[12] = 'f';
			header[13] = 'm';
			header[14] = 't';
			header[15] = ' ';
			header[16] = 16;
			header[17] = 0;
			header[18] = 0;
			header[19] = 0;
			header[20] = 1;
			header[21] = 0;
			header[22] = (byte) channels;
			header[23] = 0;
			header[24] = (byte) (sampleRate & 0xff);
			header[25] = (byte) ((sampleRate >> 8) & 0xff);
			header[26] = (byte) ((sampleRate >> 16) & 0xff);
			header[27] = (byte) ((sampleRate >> 24) & 0xff);
			header[28] = (byte) ((bitrate / 8) & 0xff);
			header[29] = (byte) (((bitrate / 8) >> 8) & 0xff);
			header[30] = (byte) (((bitrate / 8) >> 16) & 0xff);
			header[31] = (byte) (((bitrate / 8) >> 24) & 0xff);
			header[32] = (byte) ((channels * 16) / 8);
			header[33] = 0;
			header[34] = 16;
			header[35] = 0;
			header[36] = 'd';
			header[37] = 'a';
			header[38] = 't';
			header[39] = 'a';
			header[40] = (byte) ((pcm.len * 2) & 0xff);
			header[41] = (byte) (((pcm.len * 2) >> 8) & 0xff);
			header[42] = (byte) (((pcm.len * 2) >> 16) & 0xff);
			header[43] = (byte) (((pcm.len * 2) >> 24) & 0xff);
		}

		@Override
		public int available() {
			return 44 + pcm.len * 2 - pos;
		}

		@Override
		public synchronized void mark(int readlimit) {
			mark = pos;
		}

		@Override
		public synchronized void reset() {
			pos = mark;
		}

		@Override
		public long skip(long n) {
			if (n < 0) {
				return 0;
			}
			if (44 + pcm.len * 2 - pos < n) {
				pos = 44 + pcm.len * 2;
				return 44 + pcm.len * 2 - pos;
			}
			pos += n;
			return n;
		}

		@Override
		public boolean markSupported() {
			return true;
		}

		@Override
		public int read() {
			int result = -1;
			if (pos < 44) {
				result = 0x00ff & header[pos];
				pos++;
			} else if (pos < 44 + pcm.len * 2) {
				short s = pcm.sample[(pos - 44) / 2 + pcm.start];
				if (pos % 2 == 0) {
					result = (s & 0x00ff);
				} else {
					result = ((s & 0xff00) >>> 8);
				}
				pos++;
			}
			// System.out.println("read : " + pos + " data : " + result);
			return result;
		}
	}
}
