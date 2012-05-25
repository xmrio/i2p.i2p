package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.crypto.DSAEngine;
import net.i2p.util.Clock;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * Defines the set of leases a destination currently has.
 *
 * Support encryption and decryption with a supplied key.
 * Only the gateways and tunnel IDs in the individual
 * leases are encrypted.
 *
 * Encrypted leases are not indicated as such.
 * The only way to tell a lease is encrypted is to
 * determine that the listed gateways do not exist.
 * Routers wishing to decrypt a leaseset must have the
 * desthash and key in their keyring.
 * This is required for the local router as well, since
 * the encryption is done on the client side of I2CP, the
 * router must decrypt it back again for local usage
 * (but not for transmission to the floodfills)
 *
 * Decrypted leases are only available through the getLease()
 * method, so that storage and network transmission via
 * writeBytes() will output the original encrypted
 * leases and the original leaseset signature.
 *
 * @author jrandom
 */
public class LeaseSet extends DataStructureImpl {
    private final static Log _log = new Log(LeaseSet.class);
    private Destination _destination;
    private PublicKey _encryptionKey;
    private SigningPublicKey _signingKey;
    // Keep leases in the order received, or else signature verification will fail!
    private List _leases;
    private Signature _signature;
    private volatile Hash _currentRoutingKey;
    private volatile byte[] _routingKeyGenMod;
    private boolean _receivedAsPublished;
    // Store these since isCurrent() and getEarliestLeaseDate() are called frequently
    private long _firstExpiration;
    private long _lastExpiration;
    private List _decryptedLeases;
    private boolean _decrypted;
    private boolean _checked;

    /** This seems like plenty  */
    public final static int MAX_LEASES = 6;

    public LeaseSet() {
        setDestination(null);
        setEncryptionKey(null);
        setSigningKey(null);
        setSignature(null);
        setRoutingKey(null);
        _leases = new ArrayList();
        _routingKeyGenMod = null;
        _receivedAsPublished = false;
        _firstExpiration = Long.MAX_VALUE;
        _lastExpiration = 0;
        _decrypted = false;
        _checked = false;
    }

    public Destination getDestination() {
        return _destination;
    }

    public void setDestination(Destination dest) {
        _destination = dest;
    }

    public PublicKey getEncryptionKey() {
        return _encryptionKey;
    }

    public void setEncryptionKey(PublicKey encryptionKey) {
        _encryptionKey = encryptionKey;
    }

    public SigningPublicKey getSigningKey() {
        return _signingKey;
    }

    public void setSigningKey(SigningPublicKey key) {
        _signingKey = key;
    }
    
    /**
     * If true, we received this LeaseSet by a remote peer publishing it to
     * us, rather than by searching for it ourselves or locally creating it.
     *
     */
    public boolean getReceivedAsPublished() { return _receivedAsPublished; }
    public void setReceivedAsPublished(boolean received) { _receivedAsPublished = received; }

    public void addLease(Lease lease) {
        if (lease == null) throw new IllegalArgumentException("erm, null lease");
        if (lease.getGateway() == null) throw new IllegalArgumentException("erm, lease has no gateway");
        if (lease.getTunnelId() == null) throw new IllegalArgumentException("erm, lease has no tunnel");
        if (_leases.size() > MAX_LEASES)
            throw new IllegalArgumentException("Too many leases - max is " + MAX_LEASES);
        _leases.add(lease);
        long expire = lease.getEndDate().getTime();
        if (expire < _firstExpiration)
            _firstExpiration = expire;
        if (expire > _lastExpiration)
            _lastExpiration = expire;
    }

    public int getLeaseCount() {
        if (isEncrypted())
            return _leases.size() - 1;
        else
            return _leases.size();
    }

    public Lease getLease(int index) {
        if (isEncrypted())
            return (Lease) _decryptedLeases.get(index);
        else
            return (Lease) _leases.get(index);
    }

    public Signature getSignature() {
        return _signature;
    }

    public void setSignature(Signature sig) {
        _signature = sig;
    }

    /**
     * Get the routing key for the structure using the current modifier in the RoutingKeyGenerator.
     * This only calculates a new one when necessary though (if the generator's key modifier changes)
     *
     */
    public Hash getRoutingKey() {
        RoutingKeyGenerator gen = RoutingKeyGenerator.getInstance();
        if ((gen.getModData() == null) || (_routingKeyGenMod == null)
            || (!DataHelper.eq(gen.getModData(), _routingKeyGenMod))) {
            setRoutingKey(gen.getRoutingKey(getDestination().calculateHash()));
            _routingKeyGenMod = gen.getModData();
        }
        return _currentRoutingKey;
    }

    public void setRoutingKey(Hash key) {
        _currentRoutingKey = key;
    }

    public boolean validateRoutingKey() {
        Hash destKey = getDestination().calculateHash();
        Hash rk = RoutingKeyGenerator.getInstance().getRoutingKey(destKey);
        if (rk.equals(getRoutingKey()))
            return true;

        return false;
    }

    /**
     * Retrieve the end date of the earliest lease include in this leaseSet.
     * This is the date that should be used in comparisons for leaseSet age - to
     * determine which LeaseSet was published more recently (later earliestLeaseSetDate
     * means it was published later)
     *
     * @return earliest end date of any lease in the set, or -1 if there are no leases
     */
    public long getEarliestLeaseDate() {
        if (_leases.size() <= 0)
            return -1;
        return _firstExpiration;
    }

    /**
     * Sign the structure using the supplied signing key
     *
     */
    public void sign(SigningPrivateKey key) throws DataFormatException {
        byte[] bytes = getBytes();
        if (bytes == null) throw new DataFormatException("Not enough data to sign");
        // now sign with the key 
        Signature sig = DSAEngine.getInstance().sign(bytes, key);
        setSignature(sig);
    }

    /**
     * Verify that the signature matches the lease set's destination's signing public key.
     *
     * @return true only if the signature matches
     */
    public boolean verifySignature() {
        if (getSignature() == null) return false;
        if (getDestination() == null) return false;
        byte data[] = getBytes();
        if (data == null) return false;
        boolean signedByDest = DSAEngine.getInstance().verifySignature(getSignature(), data,
                                                                       getDestination().getSigningPublicKey());
        boolean signedByRevoker = false;
        if (!signedByDest) {
            signedByRevoker = DSAEngine.getInstance().verifySignature(getSignature(), data, _signingKey);
        }
        return signedByDest || signedByRevoker;
    }

    /**
     * Verify that the signature matches the lease set's destination's signing public key.
     *
     * @return true only if the signature matches
     */
    public boolean verifySignature(SigningPublicKey signingKey) {
        if (getSignature() == null) return false;
        if (getDestination() == null) return false;
        byte data[] = getBytes();
        if (data == null) return false;
        boolean signedByDest = DSAEngine.getInstance().verifySignature(getSignature(), data,
                                                                       getDestination().getSigningPublicKey());
        boolean signedByRevoker = false;
        if (!signedByDest) {
            signedByRevoker = DSAEngine.getInstance().verifySignature(getSignature(), data, signingKey);
        }
        return signedByDest || signedByRevoker;
    }

    /**
     * Determine whether ANY lease is currently valid, at least within a given
     * fudge factor 
     *
     * @param fudge milliseconds fudge factor to allow between the current time
     * @return true if there are current leases, false otherwise
     */
    public boolean isCurrent(long fudge) {
        long now = Clock.getInstance().now();
        return _lastExpiration > now - fudge;
    }

    private byte[] getBytes() {
        if ((_destination == null) || (_encryptionKey == null) || (_signingKey == null) || (_leases == null))
            return null;
        int len = PublicKey.KEYSIZE_BYTES  // dest
                + SigningPublicKey.KEYSIZE_BYTES // dest
                + 4 // cert
                + PublicKey.KEYSIZE_BYTES // encryptionKey
                + SigningPublicKey.KEYSIZE_BYTES // signingKey
                + 1
                + _leases.size() * 44; // leases
        ByteArrayOutputStream out = new ByteArrayOutputStream(len);
        try {
            _destination.writeBytes(out);
            _encryptionKey.writeBytes(out);
            _signingKey.writeBytes(out);
            DataHelper.writeLong(out, 1, _leases.size());
            //DataHelper.writeLong(out, 4, _version);
            for (Iterator iter = _leases.iterator(); iter.hasNext();) {
                Lease lease = (Lease) iter.next();
                lease.writeBytes(out);
            }
        } catch (IOException ioe) {
            return null;
        } catch (DataFormatException dfe) {
            return null;
        }
        byte rv[] = out.toByteArray();
        return rv;
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _destination = new Destination();
        _destination.readBytes(in);
        _encryptionKey = new PublicKey();
        _encryptionKey.readBytes(in);
        _signingKey = new SigningPublicKey();
        _signingKey.readBytes(in);
        int numLeases = (int) DataHelper.readLong(in, 1);
        if (numLeases > MAX_LEASES)
            throw new DataFormatException("Too many leases - max is " + MAX_LEASES);
        //_version = DataHelper.readLong(in, 4);
        _leases.clear();
        for (int i = 0; i < numLeases; i++) {
            Lease lease = new Lease();
            lease.readBytes(in);
            addLease(lease);
        }
        _signature = new Signature();
        _signature.readBytes(in);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if ((_destination == null) || (_encryptionKey == null) || (_signingKey == null) || (_leases == null)
            || (_signature == null)) throw new DataFormatException("Not enough data to write out a LeaseSet");

        _destination.writeBytes(out);
        _encryptionKey.writeBytes(out);
        _signingKey.writeBytes(out);
        DataHelper.writeLong(out, 1, _leases.size());
        //DataHelper.writeLong(out, 4, _version);
        for (Iterator iter = _leases.iterator(); iter.hasNext();) {
            Lease lease = (Lease) iter.next();
            lease.writeBytes(out);
        }
        _signature.writeBytes(out);
    }
    
    public int size() {
        return PublicKey.KEYSIZE_BYTES //destination.pubKey
             + SigningPublicKey.KEYSIZE_BYTES // destination.signPubKey
             + 2 // destination.certificate
             + PublicKey.KEYSIZE_BYTES // encryptionKey
             + SigningPublicKey.KEYSIZE_BYTES // signingKey
             + 1
             + _leases.size() * (Hash.HASH_LENGTH + 4 + 8);
    }
    
    @Override
    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof LeaseSet)) return false;
        LeaseSet ls = (LeaseSet) object;
        return DataHelper.eq(getEncryptionKey(), ls.getEncryptionKey()) &&
        //DataHelper.eq(getVersion(), ls.getVersion()) &&
               DataHelper.eq(_leases, ls._leases) && DataHelper.eq(getSignature(), ls.getSignature())
               && DataHelper.eq(getSigningKey(), ls.getSigningKey())
               && DataHelper.eq(getDestination(), ls.getDestination());

    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(getEncryptionKey()) +
        //(int)_version +
               DataHelper.hashCode(_leases) + DataHelper.hashCode(getSignature())
               + DataHelper.hashCode(getSigningKey()) + DataHelper.hashCode(getDestination());
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(128);
        buf.append("[LeaseSet: ");
        buf.append("\n\tDestination: ").append(getDestination());
        buf.append("\n\tEncryptionKey: ").append(getEncryptionKey());
        buf.append("\n\tSigningKey: ").append(getSigningKey());
        //buf.append("\n\tVersion: ").append(getVersion());
        buf.append("\n\tSignature: ").append(getSignature());
        buf.append("\n\tLeases: #").append(getLeaseCount());
        for (int i = 0; i < getLeaseCount(); i++)
            buf.append("\n\t\tLease (").append(i).append("): ").append(getLease(i));
        buf.append("]");
        return buf.toString();
    }

    private static final int DATA_LEN = Hash.HASH_LENGTH + 4;
    private static final int IV_LEN = 16;

    /**
     *  Encrypt the gateway and tunnel ID of each lease, leaving the expire dates unchanged.
     *  This adds an extra dummy lease, because AES data must be padded to 16 bytes.
     *  The fact that it is encrypted is not stored anywhere.
     *  Must be called after all the leases are in place, but before sign().
     */
    public void encrypt(SessionKey key) {
        if (_log.shouldLog(Log.WARN))
            _log.warn("encrypting lease: " + _destination.calculateHash());
        try {
            encryp(key);
        } catch (DataFormatException dfe) {
            _log.error("Error encrypting lease: " + _destination.calculateHash());
        } catch (IOException ioe) {
            _log.error("Error encrypting lease: " + _destination.calculateHash());
        }
    }

    /**
     *  - Put the {Gateway Hash, TunnelID} pairs for all the leases in a buffer
     *  - Pad with random data to a multiple of 16 bytes
     *  - Use the first part of the dest's public key as an IV
     *  - Encrypt
     *  - Pad with random data to a multiple of 36 bytes
     *  - Add an extra lease
     *  - Replace the Hash and TunnelID in each Lease
     */
    private void encryp(SessionKey key) throws DataFormatException, IOException {
        int size = _leases.size();
        if (size < 1 || size > MAX_LEASES-1)
            throw new IllegalArgumentException("Bad number of leases for encryption");
        int datalen = ((DATA_LEN * size / 16) + 1) * 16;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(datalen);
        for (int i = 0; i < size; i++) {
            ((Lease)_leases.get(i)).getGateway().writeBytes(baos);
            ((Lease)_leases.get(i)).getTunnelId().writeBytes(baos);
        }
        // pad out to multiple of 16 with random data before encryption
        int padlen = datalen - (DATA_LEN * size);
        byte[] pad = new byte[padlen];
        RandomSource.getInstance().nextBytes(pad);
        baos.write(pad);
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(_destination.getPublicKey().getData(), 0, iv, 0, IV_LEN);
        byte[] enc = new byte[DATA_LEN * (size + 1)];
        I2PAppContext.getGlobalContext().aes().encrypt(baos.toByteArray(), 0, enc, 0, key, iv, datalen);
        // pad out to multiple of 36 with random data after encryption
        // (even for 4 leases, where 36*4 is a multiple of 16, we add another, just to be consistent)
        padlen = enc.length - datalen;
        pad = new byte[padlen];
        RandomSource.getInstance().nextBytes(pad);
        System.arraycopy(pad, 0, enc, datalen, padlen);
        // add the padded lease...
        Lease padLease = new Lease();
        padLease.setEndDate(((Lease)_leases.get(0)).getEndDate());
        _leases.add(padLease);
        // ...and replace all the gateways and tunnel ids
        ByteArrayInputStream bais = new ByteArrayInputStream(enc);
        for (int i = 0; i < size+1; i++) {
            Hash h = new Hash();
            h.readBytes(bais);
            ((Lease)_leases.get(i)).setGateway(h);
            TunnelId t = new TunnelId();
            t.readBytes(bais);
            ((Lease)_leases.get(i)).setTunnelId(t);
        }
    }

    /**
     *  Decrypt the leases, except for the last one which is partially padding.
     *  Store the new decrypted leases in a backing store,
     *  and keep the original leases so that verify() still works and the
     *  encrypted leaseset can be sent on to others (via writeBytes())
     */
    private void decrypt(SessionKey key) throws DataFormatException, IOException {
        if (_log.shouldLog(Log.WARN))
            _log.warn("decrypting lease: " + _destination.calculateHash());
        int size = _leases.size();
        if (size < 2)
            throw new DataFormatException("Bad number of leases for decryption");
        int datalen = DATA_LEN * size;
        ByteArrayOutputStream baos = new ByteArrayOutputStream(datalen);
        for (int i = 0; i < size; i++) {
            ((Lease)_leases.get(i)).getGateway().writeBytes(baos);
            ((Lease)_leases.get(i)).getTunnelId().writeBytes(baos);
        }
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(_destination.getPublicKey().getData(), 0, iv, 0, IV_LEN);
        int enclen = ((DATA_LEN * (size - 1) / 16) + 1) * 16;
        byte[] enc = new byte[enclen];
        System.arraycopy(baos.toByteArray(), 0, enc, 0, enclen);
        byte[] dec = new byte[enclen];
        I2PAppContext.getGlobalContext().aes().decrypt(enc, 0, dec, 0, key, iv, enclen);
        ByteArrayInputStream bais = new ByteArrayInputStream(dec);
        _decryptedLeases = new ArrayList(size - 1);
        for (int i = 0; i < size-1; i++) {
            Lease l = new Lease();
            Hash h = new Hash();
            h.readBytes(bais);
            l.setGateway(h);
            TunnelId t = new TunnelId();
            t.readBytes(bais);
            l.setTunnelId(t);
            l.setEndDate(((Lease)_leases.get(i)).getEndDate());
            _decryptedLeases.add(l);
        }
    }

    /**
     * @return true if it was encrypted, and we decrypted it successfully.
     * Decrypts on first call.
     */
    private synchronized boolean isEncrypted() {
        if (_decrypted)
           return true;
        if (_checked || _destination == null)
           return false;
        SessionKey key = I2PAppContext.getGlobalContext().keyRing().get(_destination.calculateHash());
        if (key != null) {
            try {
                decrypt(key);
                _decrypted = true;
            } catch (DataFormatException dfe) {
                _log.error("Error decrypting lease: " + _destination.calculateHash() + dfe);
            } catch (IOException ioe) {
                _log.error("Error decrypting lease: " + _destination.calculateHash() + ioe);
            }
        }
        _checked = true;
        return _decrypted;
    }
}
